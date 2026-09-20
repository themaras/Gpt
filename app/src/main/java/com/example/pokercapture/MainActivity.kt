package com.example.pokercapture

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var resultAction: TextView
    private lateinit var resultMeta: TextView
    private lateinit var latency: TextView
    private lateinit var capButton: Button
    private lateinit var apiKeyInput: EditText
    private var captureStarted = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra(CaptureService.EXTRA_STATE)) {
                "ANALYZING" -> {
                    capButton.isEnabled = false
                    capButton.text = "ANALYZING…"
                    resultAction.text = "…"
                    resultMeta.text = "Reading table"
                    latency.text = ""
                }
                "RESULT" -> {
                    capButton.isEnabled = true
                    capButton.text = "CAP"
                    resultAction.text =
                        intent.getStringExtra(CaptureService.EXTRA_ACTION_TEXT) ?: "UNCLEAR"
                    resultMeta.text = intent.getStringExtra(CaptureService.EXTRA_META) ?: ""
                    val ms = intent.getLongExtra(CaptureService.EXTRA_LATENCY_MS, 0L)
                    latency.text = if (ms > 0) String.format("%.1f sec", ms / 1000.0) else ""
                }
                "ERROR" -> {
                    capButton.isEnabled = true
                    capButton.text = "CAP"
                    resultAction.text = "RETRY"
                    resultMeta.text =
                        intent.getStringExtra(CaptureService.EXTRA_ERROR) ?: "Request failed"
                    val ms = intent.getLongExtra(CaptureService.EXTRA_LATENCY_MS, 0L)
                    latency.text = if (ms > 0) String.format("%.1f sec", ms / 1000.0) else ""
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
                captureStarted = true
                status.text = "READY"
                capButton.isEnabled = true
            } else {
                captureStarted = false
                status.text = "Screen capture permission denied"
                capButton.isEnabled = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        resultAction = findViewById(R.id.resultAction)
        resultMeta = findViewById(R.id.resultMeta)
        latency = findViewById(R.id.latency)
        capButton = findViewById(R.id.capButton)
        apiKeyInput = findViewById(R.id.apiKeyInput)

        apiKeyInput.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        if (ApiKeyStore.hasKey(this)) apiKeyInput.setText("••••••••••••••••")

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

        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            val entered = apiKeyInput.text.toString().trim()
            if (entered.isBlank() || entered.startsWith("•")) {
                Toast.makeText(this, "Paste a new API key first", Toast.LENGTH_SHORT).show()
            } else {
                ApiKeyStore.save(this, entered)
                apiKeyInput.setText("••••••••••••••••")
                Toast.makeText(
                    this,
                    "API key saved encrypted on this device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        findViewById<Button>(R.id.startButton).setOnClickListener {
            if (!ApiKeyStore.hasKey(this)) {
                Toast.makeText(this, "Save your OpenAI API key first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mgr =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            status.text = "Choose ENTIRE SCREEN"
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
            captureStarted = false
            capButton.isEnabled = false
            status.text = "STOPPED"
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
        try {
            unregisterReceiver(resultReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }
}
