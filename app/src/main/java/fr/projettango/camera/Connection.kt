package fr.projettango.camera

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit

/** Settings pushed by the middleware (camera section of its configuration). */
data class RemoteSettings(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Double = 15.0,
    val jpegQuality: Int = 80,
    val lens: String = "back",
    val focus: String = "auto",
    val exposureCompensation: Int = 0,
    val torch: Boolean = false,
    val maxExposureMs: Double? = null,
) {
    companion object {
        fun from(j: JSONObject, cur: RemoteSettings) = RemoteSettings(
            width = j.optInt("width", cur.width).takeIf { it > 0 } ?: cur.width,
            height = j.optInt("height", cur.height).takeIf { it > 0 } ?: cur.height,
            fps = j.optDouble("fps", cur.fps).takeIf { !it.isNaN() && it > 0 } ?: cur.fps,
            jpegQuality = j.optInt("jpeg_quality", cur.jpegQuality),
            lens = j.optString("lens", cur.lens),
            focus = j.optString("focus", cur.focus),
            exposureCompensation = j.optInt("exposure_compensation", cur.exposureCompensation),
            torch = j.optBoolean("torch", cur.torch),
            maxExposureMs = if (!j.has("max_exposure_ms")) cur.maxExposureMs
                            else if (j.isNull("max_exposure_ms")) null else j.optDouble("max_exposure_ms"),
        )
    }
}

/**
 * Finds the middleware and keeps a WebSocket to it open forever:
 * last known server → server typed / scanned → UDP discovery, with retries every 1–5 s.
 */
class Connection(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val onSettings: (RemoteSettings) -> Unit,
    private val onState: (State, String) -> Unit,
) {
    enum class State { SEARCHING, CONNECTING, CONNECTED, BAD_CODE, BUSY, STOPPED }

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(5, TimeUnit.SECONDS)   // detects a dead Wi-Fi link within ~10 s
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile var connected = false; private set
    @Volatile var serverName = ""; private set
    private var settings = RemoteSettings()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    private var startedAt = 0L

    fun start() {
        if (job?.isActive == true) return
        startedAt = SystemClock.elapsedRealtime()
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        ws?.close(1000, "stop")
        ws = null
        connected = false
        onState(State.STOPPED, "")
    }

    /** Network came back / settings changed: retry now instead of waiting for the backoff. */
    fun retryNow() {
        // NetworkCallback.onAvailable fires right after registration: not a reason to hurry
        if (SystemClock.elapsedRealtime() - startedAt < 3000) return
        wake.trySend(Unit)
        if (!connected) ws?.cancel()
    }

    /** Queue a JPEG frame. Returns false (frame dropped) when not connected or the link is congested. */
    fun sendFrame(jpeg: ByteArray): Boolean {
        val w = ws ?: return false
        if (!connected || w.queueSize() > MAX_QUEUE) return false
        return w.send(jpeg.toByteString())
    }

    fun sendText(json: JSONObject) {
        if (connected) ws?.send(json.toString())
    }

    private suspend fun runLoop() {
        var backoff = 1000L
        while (scope.isActive) {
            val target = findServer()
            if (target == null) {
                onState(State.SEARCHING, "looking for the middleware on the network…")
                waitOrWake(2000)
                continue
            }
            onState(State.CONNECTING, "${target.first}:${target.second}")
            when (val result = session(target.first, target.second)) {
                "welcome" -> backoff = 1000L
                "bad_code" -> backoff = 10_000L
                "busy" -> backoff = 5000L
                "replaced" -> backoff = 300L
                else -> Log.i(TAG, "session ended: $result")
            }
            if (!connected) waitOrWake(backoff)
            backoff = (backoff * 2).coerceAtMost(5000L).coerceAtLeast(1000L)
        }
    }

    private suspend fun waitOrWake(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    /** One WebSocket session; returns how it ended. */
    private suspend fun session(host: String, port: Int): String {
        val ended = CompletableDeferred<String>()
        var outcome = "closed"
        val request = Request.Builder().url("ws://$host:$port/ws").build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject()
                    .put("type", "hello").put("code", prefs.code).put("device", prefs.deviceId)
                    .put("name", prefs.deviceName).put("platform", "android")
                    .put("app_version", BuildConfig.VERSION_NAME).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (j.optString("type")) {
                    "welcome" -> {
                        outcome = "welcome"
                        connected = true
                        serverName = j.optString("server", host)
                        prefs.lastServer = "$host:$port"
                        onState(State.CONNECTED, serverName)
                    }
                    "settings" -> {
                        settings = RemoteSettings.from(j, settings)
                        onSettings(settings)
                    }
                    "error" -> {
                        outcome = j.optString("reason", "error")
                        when (outcome) {
                            "bad_code" -> onState(State.BAD_CODE, "wrong pairing code")
                            "busy" -> onState(State.BUSY, "another phone is already streaming")
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended.complete(outcome)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(outcome)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.i(TAG, "websocket failure: $t")
                // a refusal (bad_code, busy…) is followed by the server closing the socket: keep its reason
                ended.complete(if (outcome == "welcome") "lost" else outcome)
            }
        })
        ws = socket
        try {
            return ended.await()
        } finally {
            connected = false
            if (ws === socket) ws = null
            socket.cancel()
        }
    }

    // ------------------------------------------------------------------ discovery

    private suspend fun findServer(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        val candidates = listOf(prefs.lastServer, prefs.server).mapNotNull { Prefs.parseHostPort(it) }.distinct()
        for ((h, p) in candidates) if (reachable(h, p)) return@withContext h to p
        discover()?.also { prefs.lastServer = "${it.first}:${it.second}" }
    }

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1500); true }
    } catch (e: Exception) {
        false
    }

    /** Broadcast {"type":"tango_discover"} and take the first middleware that answers. */
    private fun discover(): Pair<String, Int>? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tango-discovery").apply { setReferenceCounted(false) }
        return try {
            lock.acquire()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 1500
                val probe = JSONObject().put("type", "tango_discover").put("v", 1).toString().toByteArray()
                val ports = (listOfNotNull(Prefs.parseHostPort(prefs.server)?.second) + Prefs.DEFAULT_PORT).distinct()
                for (addr in broadcastAddresses()) for (port in ports) {
                    runCatching { sock.send(DatagramPacket(probe, probe.size, addr, port)) }
                }
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + 1500
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (e: Exception) { break }
                    val j = runCatching { JSONObject(String(pkt.data, 0, pkt.length)) }.getOrNull() ?: continue
                    if (j.optString("type") != "tango_here") continue
                    return pkt.address.hostAddress!! to j.optInt("port", Prefs.DEFAULT_PORT)
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "discovery: $e")
            null
        } finally {
            runCatching { lock.release() }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        runCatching {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (ia in nif.interfaceAddresses) {
                    if (ia.address is Inet4Address) ia.broadcast?.let { out += it }
                }
            }
        }
        return out.distinct()
    }

    companion object {
        private const val TAG = "TangoConnection"
        private const val MAX_QUEUE = 1_500_000L
    }
}
