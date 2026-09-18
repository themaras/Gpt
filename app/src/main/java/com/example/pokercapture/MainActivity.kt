package com.example.pokercapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.Manifest
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var lastSharedId = -1L
    private val screenshotObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            shareLatestScreenshot()
        }
    }
    private lateinit var status: TextView
    private lateinit var frames: TextView
    private var waitingForOverlayPermission = false

    private fun requestScreenCapturePermission() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        status.text = "Waiting for screen capture permission"
        captureLauncher.launch(mgr.createScreenCaptureIntent())
    }
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val i = Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_DATA, result.data)
            }
            startForegroundService(i)
            status.text = "Capturing"
            refreshFrameCount()
        } else status.text = "Permission denied"
    }
    private fun refreshFrameCount() {
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        if (!prefs.contains("frames_saved")) {
            val oldDir = java.io.File(getExternalFilesDir(null), "frames")
            val oldCount = oldDir.listFiles { file -> file.extension.equals("jpg", true) }?.size ?: 0
            prefs.edit().putInt("frames_saved", oldCount).apply()
        }
        frames.text = "Frames saved: " + prefs.getInt("frames_saved", 0)
    }
    private fun shareLatestScreenshot() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) return
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH)
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
            if (!cur.moveToFirst()) return
            val id = cur.getLong(0)
            val name = cur.getString(1) ?: ""
            val rel = cur.getString(2) ?: ""
            if (id == lastSharedId || (!name.contains("screenshot", true) && !rel.contains("screenshot", true))) return
            lastSharedId = id
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.openai.chatgpt")
            }
            try { startActivity(send) } catch (_: Exception) {
                send.setPackage(null)
                startActivity(Intent.createChooser(send, "Send screenshot"))
            }
        }
    }

    private fun enableScreenshotBridge() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_MEDIA_IMAGES), 42)
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, screenshotObserver)
    }

    override fun onResume() {
        super.onResume()
        refreshFrameCount()
        if (waitingForOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            requestScreenCapturePermission()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        frames = findViewById(R.id.frames)
        val cropGroup = findViewById<android.widget.RadioGroup>(R.id.cropGroup)
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        when (prefs.getString("crop_mode", "RIGHT")) {
            "LEFT" -> findViewById<android.widget.RadioButton>(R.id.cropLeft).isChecked = true
            "TOP" -> findViewById<android.widget.RadioButton>(R.id.cropTop).isChecked = true
            "BOTTOM" -> findViewById<android.widget.RadioButton>(R.id.cropBottom).isChecked = true
            else -> findViewById<android.widget.RadioButton>(R.id.cropRight).isChecked = true
        }
        cropGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.cropLeft -> "LEFT"
                R.id.cropTop -> "TOP"
                R.id.cropBottom -> "BOTTOM"
                else -> "RIGHT"
            }
            prefs.edit().putString("crop_mode", mode).apply()
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                waitingForOverlayPermission = true
                status.text = "Allow Display over other apps"
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                }
                return@setOnClickListener
            }
            requestScreenCapturePermission()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
            status.text = "Stopped"
        }
    }
}
