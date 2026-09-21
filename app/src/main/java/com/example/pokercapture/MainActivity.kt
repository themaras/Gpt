package com.example.pokercapture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var apiStatus: TextView
    private lateinit var resultAction: TextView
    private lateinit var resultMeta: TextView
    private lateinit var latency: TextView
    private lateinit var requestInfo: TextView
    private lateinit var boardText: TextView
    private lateinit var strategyText: TextView
    private lateinit var capButton: Button
    private var captureStarted = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(CaptureService.EXTRA_STATE) ?: return
            val imageKb = intent.getIntExtra(CaptureService.EXTRA_IMAGE_KB, 0)
            val http = intent.getIntExtra(CaptureService.EXTRA_HTTP_CODE, 0)

            when (state) {
                "CAPTURE_READY" -> {
                    captureStarted = true
                    status.text = "CAPTURE READY"
                    apiStatus.text = "API: idle"
                    capButton.isEnabled = true
                }
                "ANALYZING" -> {
                    capButton.isEnabled = false
                    capButton.text = "WORKING…"
                    resultAction.text = "…"
                    resultMeta.text = "Preparing crop"
                    apiStatus.text = "API: preparing request"
                    latency.text = ""
                }
                "SENDING" -> {
                    resultMeta.text = if (imageKb > 0) "Image: ${imageKb} KB" else "Sending image"
                    apiStatus.text = "API: SENDING…"
                }
                "REQUEST_SENT" -> {
                    apiStatus.text = "API: REQUEST SENT ✓"
                    if (imageKb > 0) resultMeta.text = "Image: ${imageKb} KB"
                }
                "WAITING_API" -> {
                    apiStatus.text = "API: WAITING RESPONSE…"
                }
                "RESULT" -> {
                    capButton.isEnabled = true
                    capButton.text = "CAP"
                    resultAction.text =
                        intent.getStringExtra(CaptureService.EXTRA_ACTION_TEXT) ?: "UNCLEAR"
                    resultMeta.text = intent.getStringExtra(CaptureService.EXTRA_META) ?: ""
                    boardText.text = "BOARD: " + (intent.getStringExtra(CaptureService.EXTRA_BOARD) ?: "?")
                    strategyText.text = intent.getStringExtra(CaptureService.EXTRA_STRATEGY) ?: ""
                    val ms = intent.getLongExtra(CaptureService.EXTRA_LATENCY_MS, 0L)
                    latency.text = if (ms > 0) String.format("%.1f sec", ms / 1000.0) else ""
                    apiStatus.text = if (http > 0) "API: RESPONSE OK • HTTP $http" else "API: RESPONSE OK"
                    updateRequestInfo()
                }
                "ERROR" -> {
                    capButton.isEnabled = captureStarted
                    capButton.text = "CAP"
                    resultAction.text = "RETRY"
                    resultMeta.text =
                        intent.getStringExtra(CaptureService.EXTRA_ERROR) ?: "Request failed"
                    boardText.text = ""
                    strategyText.text = ""
                    val ms = intent.getLongExtra(CaptureService.EXTRA_LATENCY_MS, 0L)
                    latency.text = if (ms > 0) String.format("%.1f sec", ms / 1000.0) else ""
                    apiStatus.text = if (http > 0) "API: ERROR HTTP $http" else "API: ERROR"
                    updateRequestInfo()
                }
                "STOPPED" -> {
                    captureStarted = false
                    capButton.isEnabled = false
                    capButton.text = "CAP"
                    status.text = "STOPPED"
                    apiStatus.text = "API: idle"
                }
            }
        }
    }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startForegroundService(Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(CaptureService.EXTRA_DATA, result.data)
                })
                status.text = "STARTING CAPTURE…"
            } else {
                captureStarted = false
                status.text = "CAPTURE BLOCKED / DENIED"
                apiStatus.text = "API: not started"
                capButton.isEnabled = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        apiStatus = findViewById(R.id.apiStatus)
        resultAction = findViewById(R.id.resultAction)
        resultMeta = findViewById(R.id.resultMeta)
        latency = findViewById(R.id.latency)
        requestInfo = findViewById(R.id.requestInfo)
        boardText = findViewById(R.id.boardText)
        strategyText = findViewById(R.id.strategyText)
        capButton = findViewById(R.id.capButton)

        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        val cropGroup = findViewById<RadioGroup>(R.id.cropGroup)

        when (prefs.getString("crop_mode", "LEFT")) {
            "RIGHT" -> findViewById<RadioButton>(R.id.cropRight).isChecked = true
            "TOP" -> findViewById<RadioButton>(R.id.cropTop).isChecked = true
            "BOTTOM" -> findViewById<RadioButton>(R.id.cropBottom).isChecked = true
            else -> findViewById<RadioButton>(R.id.cropLeft).isChecked = true
        }

        cropGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.cropRight -> "RIGHT"
                R.id.cropTop -> "TOP"
                R.id.cropBottom -> "BOTTOM"
                else -> "LEFT"
            }
            prefs.edit().putString("crop_mode", mode).apply()
        }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            val provider = prefs.getString("provider", "GEMINI") ?: "GEMINI"
            val hasKey = if (provider == "OPENAI") ApiKeyStore.hasKey(this) else GeminiKeyStore.hasKey(this)
            if (!hasKey) {
                Toast.makeText(this, "Open Settings and save the selected provider API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mgr =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "WAITING FOR ANDROID CAPTURE PERMISSION"
            captureLauncher.launch(mgr.createScreenCaptureIntent())
        }

        capButton.isEnabled = false
        capButton.setOnClickListener {
            if (!captureStarted) {
                Toast.makeText(this, "Start screen capture first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_ANALYZE
            })
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_STOP
            })
        }

        updateRequestInfo()
    }
    private fun updateRequestInfo() {
        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        val count = prefs.getInt("api_request_count", 0)
        val last = prefs.getLong("api_last_request_at", 0L)
        val lastText = if (last > 0L) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(last))
        } else {
            "—"
        }
        requestInfo.text = "Requests: $count • Last: $lastText"
    }

    override fun onResume() {
        super.onResume()
        updateRequestInfo()
        val provider = getSharedPreferences("capture", MODE_PRIVATE)
            .getString("provider", "GEMINI") ?: "GEMINI"
        providerLabel.text = if (provider == "OPENAI") {
            "OpenAI • GPT-5.6 Sol • 1 region image"
        } else {
            "Gemini • Gemini 3.8 Flash • 1 region image"
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(CaptureService.ACTION_ANALYSIS_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(resultReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(resultReceiver) } catch (_: Exception) {}
        super.onStop()
    }
}
