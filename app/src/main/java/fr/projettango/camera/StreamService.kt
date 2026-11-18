package fr.projettango.camera

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service owning the camera and the connection, so streaming continues with the
 * screen off or another app in front. Frames are sent in the sensor orientation (landscape on
 * nearly every phone), whatever the phone orientation: rotate them in the middleware if needed.
 */
class StreamService : LifecycleService() {

    data class Status(
        val running: Boolean = false,
        val state: Connection.State = Connection.State.STOPPED,
        val message: String = "",
        val fps: Double = 0.0,
        val width: Int = 0,
        val height: Int = 0,
        val cameraError: String? = null,
    )

    private lateinit var prefs: Prefs
    private lateinit var connection: Connection
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var boundKey = ""
    @Volatile private var settings = RemoteSettings()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // frame pacing / stats (analysis thread)
    private var lastSent = 0L
    private var sentCount = 0
    @Volatile private var cameraCount = 0
    @Volatile private var encodeMs = 0.0
    @Volatile private var bytesSent = 0L
    @Volatile private var exposureMs = 0.0
    @Volatile private var exposureNs = 0L
    @Volatile private var meanY = 0.0
    @Volatile private var exposure: ExposureController? = null
    private var exposureKey = ""
    @Volatile private var iso = 0
    private var nv21: ByteArray? = null
    private val jpegOut = ByteArrayOutputStream(256 * 1024)

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        connection = Connection(this, prefs, scope, ::onSettings) { state, msg ->
            publish { it.copy(state = state, message = msg) }
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            prefs.streaming = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!status.running) {
            publish { Status(running = true, state = Connection.State.SEARCHING) }
            acquireLocks()
            watchNetwork()
            connection.start()
            bindCamera()
            scope.launch { statsLoop() }
        } else {
            connection.retryNow()
        }
        return START_STICKY  // restarted by the system if killed
    }

    override fun onDestroy() {
        connection.stop()
        scope.cancel()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        netCallback?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        publish { Status() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground + locks

    private fun goForeground(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW))
        }
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (e: Exception) {
            // Android 12+ refuses a camera service started from the background (e.g. at boot
            // without the "display over other apps" permission): ask the user to open the app.
            Log.w(TAG, "startForeground refused: $e")
            BootReceiver.notifyTapToStart(this)
            false
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val s = status
        val text = when (s.state) {
            Connection.State.CONNECTED -> getString(R.string.notif_streaming, s.message, s.fps)
            Connection.State.BAD_CODE -> getString(R.string.state_bad_code)
            else -> getString(R.string.state_searching)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_camera)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    private fun updateNotification() {
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wifi.createWifiLock(mode, "tango:wifi").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tango:stream").apply { setReferenceCounted(false); acquire() }
    }

    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = connection.retryNow()
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
        netCallback = cb
    }

    // ------------------------------------------------------------------ camera

    private fun onSettings(s: RemoteSettings) {
        settings = s
        ContextCompat.getMainExecutor(this).execute { bindCamera() }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val s = settings
        val key = "${s.lens}/${s.width}x${s.height}/${s.fps}/${s.focus}"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get().also { cameraProvider = it }
                if (key != boundKey || camera == null) {
                    val builder = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setResolutionSelector(ResolutionSelector.Builder()
                            // match the requested shape: 1280x720 must not become 1280x960
                            .setAspectRatioStrategy(if (s.width * 3 == s.height * 4) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
                                                    else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy(Size(s.width, s.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build())
                    val ext = Camera2Interop.Extender(builder)
                    val selector = if (s.lens == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    // Pin the frame rate: in dim light auto-exposure otherwise lengthens the exposure
                    // and drops to ~15 fps. A short exposure also means less motion blur on moving tags.
                    val info = selector.filter(provider.availableCameraInfos).firstOrNull()
                    val ranges = info?.let { Camera2CameraInfo.from(it).getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) }
                    chooseFpsRange(ranges, s.fps.toInt())?.let {
                        Log.i(TAG, "AE target fps range $it (available: ${ranges?.joinToString()})")
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
                    }
                    // exposure time / ISO of every frame: lets the middleware judge the light level
                    ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposureNs = it; exposureMs = it / 1e6 }
                            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                        }
                    })
                    if (s.focus == "infinity") {
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        ext.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                    }
                    val analysis = builder.build().also { it.setAnalyzer(analysisExecutor, ::analyze) }
                    provider.unbindAll()
                    exposure = null  // new camera session: back to auto-exposure until recalibrated
                    exposureKey = ""
                    camera = provider.bindToLifecycle(this, selector, analysis)
                    boundKey = key
                }
                camera?.let { cam ->
                    if (cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(s.torch)
                    val range = cam.cameraInfo.exposureState.exposureCompensationRange
                    if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                        cam.cameraControl.setExposureCompensationIndex(s.exposureCompensation.coerceIn(range.lower, range.upper))
                    }
                    // exposure cap: (re)start the controller when its settings change
                    val ek = "${s.maxExposureMs}/${s.exposureCompensation}/${s.fps}"
                    if (ek != exposureKey) {
                        exposure?.release()
                        exposure = s.maxExposureMs?.let { ExposureController(cam, it, s.fps, s.exposureCompensation) }
                        exposureKey = ek
                    }
                }
                publish { it.copy(cameraError = null) }
            } catch (e: Exception) {
                Log.e(TAG, "camera", e)
                camera = null
                publish { it.copy(cameraError = e.message ?: e.toString()) }
                // Typically: camera busy (another app) or access refused in background. Retry later.
                scope.launch { delay(5000); ContextCompat.getMainExecutor(this@StreamService).execute { bindCamera() } }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Highest fixed range [fps, fps] if available, else the range reaching fps with the highest minimum. */
    private fun chooseFpsRange(ranges: Array<Range<Int>>?, fps: Int): Range<Int>? {
        if (ranges.isNullOrEmpty()) return null
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return ranges.filter { it.upper >= fps && it.lower <= fps }.maxByOrNull { it.lower }
            ?: ranges.maxByOrNull { it.upper }
    }

    private fun analyze(image: ImageProxy) {
        image.use {
            cameraCount++
            val yPlane = it.planes[0]
            meanY = ExposureController.meanLuma(yPlane.buffer, it.width, it.height, yPlane.rowStride)
            exposure?.onFrame(meanY, exposureNs, iso)
            val now = System.nanoTime()
            val period = (1e9 / settings.fps.coerceIn(1.0, 60.0)).toLong()
            if (now - lastSent < period * 0.9 || !connection.connected) return
            val t0 = System.nanoTime()
            val jpeg = toJpeg(it, settings.jpegQuality.coerceIn(30, 100))
            val dt = (System.nanoTime() - t0) / 1e6
            encodeMs = if (encodeMs == 0.0) dt else 0.9 * encodeMs + 0.1 * dt
            bytesSent += jpeg.size
            if (connection.sendFrame(jpeg)) {
                lastSent = now
                sentCount++
                lastJpeg = jpeg
                if (status.width != it.width) publish { s -> s.copy(width = it.width, height = it.height) }
            }
        }
    }

    /** YUV_420_888 → NV21 → JPEG (hardware-agnostic, handles row/pixel strides). */
    private fun toJpeg(image: ImageProxy, quality: Int): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val out = nv21?.takeIf { it.size == ySize + ySize / 2 } ?: ByteArray(ySize + ySize / 2).also { nv21 = it }
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yStride = yPlane.rowStride
        if (yStride == w) {
            yBuf.position(0); yBuf.get(out, 0, ySize)
        } else {
            for (row in 0 until h) { yBuf.position(row * yStride); yBuf.get(out, row * w, w) }
        }
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uvStride = vPlane.rowStride
        val uvPixel = vPlane.pixelStride
        val uvSize = ySize / 2
        if (uvPixel == 2 && uvStride == w && vBuf.capacity() >= uvSize - 1 && looksInterleaved(uBuf, vBuf)) {
            // Most phones: the V plane already is the VUVU... (NV21) chroma, one bulk copy is enough.
            vBuf.position(0); vBuf.get(out, ySize, uvSize - 1)
            out[ySize + uvSize - 1] = uBuf.get(uBuf.capacity() - 1)
        } else {
            var pos = ySize
            for (row in 0 until h / 2) {
                var idx = row * uvStride
                for (col in 0 until w / 2) {
                    out[pos++] = vBuf.get(idx)
                    out[pos++] = uBuf.get(idx)
                    idx += uvPixel
                }
            }
        }
        jpegOut.reset()
        YuvImage(out, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), quality, jpegOut)
        return jpegOut.toByteArray()
    }

    /** U and V planes are views on the same interleaved buffer (V starts one byte before U). */
    private fun looksInterleaved(u: java.nio.ByteBuffer, v: java.nio.ByteBuffer): Boolean {
        val n = minOf(u.capacity(), v.capacity() - 1)
        if (n < 64) return false
        var i = 0
        while (i < n) {
            if (u.get(i) != v.get(i + 1)) return false
            i += n / 16 + 1
        }
        return true
    }

    // ------------------------------------------------------------------ stats

    private suspend fun statsLoop() {
        var last = System.nanoTime()
        while (scope.isActive) {
            delay(2000)
            val now = System.nanoTime()
            val fps = sentCount * 1e9 / (now - last)
            val camFps = cameraCount * 1e9 / (now - last)
            val kbps = bytesSent * 8 / 1000.0 * 1e9 / (now - last)
            sentCount = 0
            cameraCount = 0
            bytesSent = 0
            last = now
            val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.let { b ->
                val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0) l * 100 / sc else null
            }
            val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }
            val j = JSONObject().put("type", "stats").put("fps", Math.round(fps * 10) / 10.0)
                .put("camera_fps", Math.round(camFps * 10) / 10.0).put("encode_ms", Math.round(encodeMs * 10) / 10.0)
                .put("kbps", Math.round(kbps))
                .put("exposure_ms", Math.round(exposureMs * 100) / 100.0).put("iso", iso)
                .put("brightness", Math.round(meanY)).put("exposure_mode", exposure?.mode?.name?.lowercase() ?: "auto").put("screen_on", (getSystemService(POWER_SERVICE) as PowerManager).isInteractive)
                .put("width", status.width).put("height", status.height).put("charging", plugged)
            level?.let { j.put("battery", it) }
            temp?.let { j.put("temperature", it / 10.0) }
            connection.sendText(j)
            publish { it.copy(fps = fps) }
            updateNotification()
        }
    }

    companion object {
        private const val TAG = "TangoStream"
        private const val CHANNEL = "stream"
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "fr.projettango.camera.STOP"

        @Volatile var status = Status(); private set
        /** Last frame sent, for the preview in the activity. */
        @Volatile var lastJpeg: ByteArray? = null; private set

        private fun publish(f: (Status) -> Status) {
            synchronized(this) { status = f(status) }
        }

        fun start(context: Context) {
            Prefs(context).streaming = true
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
        }

        fun stop(context: Context) {
            Prefs(context).streaming = false
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
