package fr.projettango.camera

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var preview: ImageView
    private lateinit var code: EditText
    private lateinit var server: EditText
    private lateinit var startStop: Button
    private lateinit var overlayBtn: Button
    private lateinit var batteryBtn: Button
    private var lastPreview: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // No FLAG_KEEP_SCREEN_ON: the screen times out / locks normally to save battery, the
        // foreground service keeps streaming. Opened at boot: show above the lock screen, because
        // Android only lets the service open the camera while the app is visible.
        if (intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) == true && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        prefs = Prefs(this)
        state = findViewById(R.id.state)
        detail = findViewById(R.id.detail)
        preview = findViewById(R.id.preview)
        code = findViewById(R.id.code)
        server = findViewById(R.id.server)
        startStop = findViewById(R.id.startStop)
        overlayBtn = findViewById(R.id.overlayPermission)
        batteryBtn = findViewById(R.id.batteryOptim)

        code.setText(prefs.code)
        server.setText(prefs.server)
        code.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { saveConnectionFields(); false } else false }
        findViewById<Button>(R.id.save).setOnClickListener { saveConnectionFields() }
        findViewById<Button>(R.id.scan).setOnClickListener { scanQr() }
        startStop.setOnClickListener {
            if (StreamService.status.running) StreamService.stop(this) else ensurePermissionsThenStart()
        }

        findViewById<CheckBox>(R.id.bootStart).apply {
            isChecked = prefs.startOnBoot
            setOnCheckedChangeListener { _, c -> prefs.startOnBoot = c }
        }
        findViewById<CheckBox>(R.id.launcherMode).apply {
            isChecked = isLauncherAliasEnabled()
            setOnCheckedChangeListener { _, c -> setLauncherMode(c) }
        }
        overlayBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryBtn.setOnClickListener {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        handleIntent(intent)
        // Streaming is the default: opening the app (or a reboot) resumes it unless the user pressed Stop.
        if (prefs.streaming) ensurePermissionsThenStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** tango-cam://pair?... or the https pairing link opened from another QR scanner app. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        applyPairingLink(data.toString())
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    private fun render() {
        val s = StreamService.status
        startStop.text = getString(if (s.running) R.string.stop else R.string.start)
        val (text, color) = when {
            !s.running -> getString(R.string.state_stopped) to Color.GRAY
            s.cameraError != null -> getString(R.string.state_camera_error, s.cameraError) to Color.rgb(0xF0, 0x64, 0x64)
            s.state == Connection.State.CONNECTED -> getString(R.string.state_connected, s.message) to Color.rgb(0x3C, 0xCB, 0x7F)
            s.state == Connection.State.BAD_CODE -> getString(R.string.state_bad_code) to Color.rgb(0xF0, 0x64, 0x64)
            s.state == Connection.State.BUSY -> getString(R.string.state_busy) to Color.rgb(0xE6, 0xA2, 0x3C)
            s.state == Connection.State.CONNECTING -> getString(R.string.state_connecting, s.message) to Color.rgb(0xE6, 0xA2, 0x3C)
            else -> getString(R.string.state_searching) to Color.rgb(0xE6, 0xA2, 0x3C)
        }
        state.text = text
        state.setTextColor(color)
        detail.text = if (s.running && s.width > 0) getString(R.string.detail_stream, s.width, s.height, s.fps) else ""
        if (s.state == Connection.State.BAD_CODE && !code.hasFocus()) code.error = getString(R.string.enter_code)

        val jpeg = StreamService.lastJpeg
        if (jpeg != null && jpeg !== lastPreview) {
            lastPreview = jpeg
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.let { preview.setImageBitmap(it) }
        }
        val needOverlay = Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)
        overlayBtn.visibility = if (needOverlay) View.VISIBLE else View.GONE
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        batteryBtn.visibility = if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun saveConnectionFields() {
        prefs.code = code.text.toString()
        prefs.server = server.text.toString()
        code.error = null
        if (StreamService.status.running) StreamService.start(this)  // wakes the connection up
        else ensurePermissionsThenStart()
    }

    private fun applyPairingLink(raw: String) {
        val (srv, c) = Prefs.parsePairingLink(raw) ?: run {
            Toast.makeText(this, R.string.qr_invalid, Toast.LENGTH_LONG).show(); return
        }
        server.setText(srv)
        if (c.isNotEmpty()) code.setText(c)
        saveConnectionFields()
        Toast.makeText(this, getString(R.string.paired_with, srv), Toast.LENGTH_SHORT).show()
    }

    private fun scanQr() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { b -> b.rawValue?.let(::applyPairingLink) }
            .addOnFailureListener { Toast.makeText(this, getString(R.string.qr_unavailable), Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ permissions

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) StreamService.start(this) else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            StreamService.start(this)
        } else {
            Toast.makeText(this, R.string.camera_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ launcher (kiosk) mode

    private val launcherAlias get() = ComponentName(this, "$packageName.LauncherAlias")

    private fun isLauncherAliasEnabled() =
        packageManager.getComponentEnabledSetting(launcherAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setLauncherMode(on: Boolean) {
        packageManager.setComponentEnabledSetting(launcherAlias,
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        if (on) {
            Toast.makeText(this, R.string.launcher_choose, Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                .onFailure { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        }
    }


    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
        private const val REQ_PERMS = 1
    }
}
