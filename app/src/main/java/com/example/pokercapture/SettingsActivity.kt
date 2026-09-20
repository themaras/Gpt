package com.example.pokercapture

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val input = findViewById<EditText>(R.id.apiKeyInput)
        val keyStatus = findViewById<TextView>(R.id.keyStatus)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

        fun refresh() {
            keyStatus.text = if (ApiKeyStore.hasKey(this)) {
                "API key: SAVED ✓"
            } else {
                "API key: NOT SET"
            }
        }

        refresh()

        findViewById<Button>(R.id.saveKeyButton).setOnClickListener {
            val entered = input.text.toString().trim()
            if (!entered.startsWith("sk-") || entered.length < 20) {
                Toast.makeText(this, "Paste a valid OpenAI API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiKeyStore.save(this, entered)
            input.text.clear()
            refresh()
            Toast.makeText(this, "Key saved encrypted on this device", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.doneButton).setOnClickListener { finish() }
    }
}
