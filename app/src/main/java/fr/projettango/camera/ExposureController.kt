package fr.projettango.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import kotlin.math.abs
import kotlin.math.pow

/**
 * Caps the exposure time to limit motion blur, and raises the ISO to keep the brightness
 * auto-exposure would have chosen.
 *
 * Phones lengthen the exposure up to the frame period before raising the gain, even in good
 * light (~30 ms at 30 fps): a robot at 0.5 m/s then moves 15 mm during one frame. Android has
 * no "max exposure" setting, so: let auto-exposure converge, remember the image brightness it
 * reached (the target), then switch to manual exposure (AE off) with the capped exposure time
 * and the equivalent ISO, and keep the brightness on target with a slow feedback loop.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class ExposureController(
    private val camera: Camera,
    private val maxExposureMs: Double,
    private val fps: Double,
    exposureCompensation: Int,
) {
    enum class Mode { CALIBRATING, MANUAL, UNSUPPORTED }

    @Volatile var mode = Mode.CALIBRATING; private set
    @Volatile var target = 0.0; private set

    private val info = Camera2CameraInfo.from(camera.cameraInfo)
    private val isoRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private val expRange = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private val ecFactor = 2.0.pow(exposureCompensation * camera.cameraInfo.exposureState.exposureCompensationStep.toDouble())
    private var frames = 0
    private var brightness = 0.0
    private var expNs = 0L
    private var iso = 0

    init {
        val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in caps || isoRange == null || expRange == null) {
            mode = Mode.UNSUPPORTED
            Log.w(TAG, "manual exposure not supported by this camera: max_exposure_ms ignored")
        }
    }

    /** Called for every frame with the mean luminance and the exposure/ISO actually used. */
    fun onFrame(meanY: Double, exposureNs: Long, sensorIso: Int) {
        if (mode == Mode.UNSUPPORTED || exposureNs <= 0 || sensorIso <= 0) return
        brightness = if (brightness == 0.0) meanY else 0.8 * brightness + 0.2 * meanY
        frames++
        when (mode) {
            Mode.CALIBRATING -> if (frames >= 20) {  // ~0.7 s: auto-exposure has settled
                target = (brightness * ecFactor).coerceIn(8.0, 200.0)  // keep what auto-exposure chose
                val cap = (maxExposureMs * 1e6).toLong()
                val e = minOf(exposureNs, cap).coerceIn(expRange!!.lower, expRange.upper)
                apply(e, (sensorIso.toDouble() * exposureNs / e).toInt())
                mode = Mode.MANUAL
                Log.i(TAG, "manual exposure: AE was ${exposureNs / 1e6} ms ISO $sensorIso, now ${e / 1e6} ms ISO $iso, target brightness $target")
            }
            Mode.MANUAL -> if (frames % 6 == 0) {  // let each change reach the image before the next
                val ratio = (target / brightness.coerceAtLeast(1.0)).coerceIn(0.6, 1.6)
                if (abs(ratio - 1.0) < 0.08) return
                // total light = exposure x gain; spend it on the longest allowed exposure first (less noise)
                val total = expNs.toDouble() * iso * ratio
                val cap = (maxExposureMs * 1e6).toLong().coerceAtMost(expRange!!.upper)
                val e = (total / isoRange!!.lower).toLong().coerceIn(expRange.lower, cap)
                apply(e, (total / e).toInt())
            }
            Mode.UNSUPPORTED -> {}
        }
    }

    private fun apply(exposureNs: Long, sensitivity: Int) {
        expNs = exposureNs
        iso = sensitivity.coerceIn(isoRange!!.lower, isoRange.upper)
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, (1e9 / fps.coerceIn(1.0, 120.0)).toLong())
                .build())
    }

    /** Back to the phone's auto-exposure (setting removed). */
    fun release() {
        Camera2CameraControl.from(camera.cameraControl).clearCaptureRequestOptions()
    }

    companion object {
        private const val TAG = "TangoExposure"

        /** Mean luminance of a Y plane, sampled on a sparse grid (cheap enough for every frame). */
        fun meanLuma(y: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): Double {
            var sum = 0L
            var n = 0
            var row = 0
            while (row < height) {
                var i = row * rowStride
                val end = i + width
                while (i < end) {
                    sum += y.get(i).toInt() and 0xFF
                    n++
                    i += 16
                }
                row += 16
            }
            return if (n > 0) sum.toDouble() / n else 0.0
        }
    }
}
