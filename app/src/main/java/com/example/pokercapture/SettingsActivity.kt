package com.example.pokercapture

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("capture", MODE_PRIVATE)
        val providerGroup = findViewById<RadioGroup>(R.id.providerGroup)
        val openAiRadio = findViewById<RadioButton>(R.id.providerOpenAI)
        val geminiRadio = findViewById<RadioButton>(R.id.providerGemini)
        val openAiInput = findViewById<EditText>(R.id.openAiKeyInput)
        val geminiInput = findViewById<EditText>(R.id.geminiKeyInput)
        val openAiStatus = findViewById<TextView>(R.id.openAiKeyStatus)
        val geminiStatus = findViewById<TextView>(R.id.geminiKeyStatus)

        openAiInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        geminiInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        when (prefs.getString("provider", "GEMINI")) {
            "OPENAI" -> openAiRadio.isChecked = true
            else -> geminiRadio.isChecked = true
        }

        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            val provider = if (checkedId == R.id.providerOpenAI) "OPENAI" else "GEMINI"
            prefs.edit().putString("provider", provider).apply()
        }

        fun refresh() {
            openAiStatus.text = if (ApiKeyStore.hasKey(this)) "OpenAI key: SAVED ✓" else "OpenAI key: NOT SET"
            geminiStatus.text = if (GeminiKeyStore.hasKey(this)) "Gemini key: SAVED ✓" else "Gemini key: NOT SET"
        }
        refresh()

        findViewById<Button>(R.id.saveOpenAiKeyButton).setOnClickListener {
            val key = openAiInput.text.toString().trim()
            if (!key.startsWith("sk-") || key.length < 20) {
                Toast.makeText(this, "Paste a valid OpenAI API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiKeyStore.save(this, key)
            openAiInput.text.clear()
            refresh()
            Toast.makeText(this, "OpenAI key saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.saveGeminiKeyButton).setOnClickListener {
            val key = geminiInput.text.toString().trim()
            if (key.length < 20) {
                Toast.makeText(this, "Paste a valid Gemini API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GeminiKeyStore.save(this, key)
            geminiInput.text.clear()
            refresh()
            Toast.makeText(this, "Gemini key saved", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }
    }
}
