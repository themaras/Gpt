package com.example.pokercapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var frames: TextView
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
        val dir = java.io.File(getExternalFilesDir(null), "frames")
        val count = dir.listFiles { f -> f.extension.equals("jpg", true) }?.size ?: 0
        frames.text = "Frames saved: $count"
    }
    override fun onResume() { super.onResume(); refreshFrameCount() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        frames = findViewById(R.id.frames)
        findViewById<Button>(R.id.startButton).setOnClickListener {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureLauncher.launch(mgr.createScreenCaptureIntent())
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply { action = CaptureService.ACTION_STOP })
            status.text = "Stopped"
        }
    }
}
