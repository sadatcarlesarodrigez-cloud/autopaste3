package com.example.autopaster

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var editText: EditText
    private lateinit var editDelay: EditText
    private lateinit var lineCountView: TextView
    private lateinit var statusAccessibility: TextView
    private lateinit var statusOverlay: TextView

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            launchOverlayService()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        editText = findViewById(R.id.editTextInput)
        editDelay = findViewById(R.id.editTextDelay)
        lineCountView = findViewById(R.id.textLineCount)
        statusAccessibility = findViewById(R.id.statusAccessibility)
        statusOverlay = findViewById(R.id.statusOverlay)

        val prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE)
        editText.setText(prefs.getString(Prefs.KEY_TEXT, "") ?: "")
        editDelay.setText(prefs.getFloat(Prefs.KEY_DELAY_SECONDS, 1f).toString())
        updateLineCount()

        editText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = updateLineCount()
        })

        findViewById<Button>(R.id.btnAccessibilitySettings).setOnClickListener {
            safeStartActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOverlayPermission).setOnClickListener {
            safeStartActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btnStartOverlay).setOnClickListener {
            val text = editText.text?.toString() ?: ""
            if (text.isBlank()) {
                Toast.makeText(this, "اول متن رو پیست کن", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val delaySeconds = editDelay.text?.toString()?.toFloatOrNull() ?: 1f
            prefs.edit()
                .putString(Prefs.KEY_TEXT, text)
                .putFloat(Prefs.KEY_DELAY_SECONDS, delaySeconds.coerceAtLeast(0.2f))
                .apply()

            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "اول اجازه نمایش روی برنامه‌ها رو بده", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "اول دسترسی Accessibility رو فعال کن", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (OverlayService.isServiceRunning) {
                Toast.makeText(this, "دکمه شناور از قبل فعاله", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (e: Exception) {
                    // Extremely unlikely, but never let a permission-request failure crash the app.
                    launchOverlayService()
                }
            } else {
                launchOverlayService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun safeStartActivity(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "این صفحه تنظیمات روی گوشیت پیدا نشد", Toast.LENGTH_LONG).show()
        }
    }

    private fun launchOverlayService() {
        try {
            startService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "دکمه شناور فعال شد. حالا برو تو اپ مقصد", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        } catch (e: Exception) {
            Toast.makeText(this, "شروع سرویس با خطا مواجه شد، دوباره امتحان کن", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLineCount() {
        val count = (editText.text?.toString() ?: "").lines().map { it.trim() }.count { it.isNotEmpty() }
        lineCountView.text = "$count خط شناسایی شد"
    }

    private fun refreshStatus() {
        val overlayOk = Settings.canDrawOverlays(this)
        statusOverlay.text = if (overlayOk) "✅ فعال" else "❌ غیرفعال"

        val accessibilityOk = isAccessibilityServiceEnabled()
        statusAccessibility.text = if (accessibilityOk) "✅ فعال" else "❌ غیرفعال"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val expected = "$packageName/${PasteAccessibilityService::class.java.canonicalName}"
            val enabledServices = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            false
        } catch (e: Exception) {
            false
        }
    }
}

object Prefs {
    const val NAME = "autopaster_prefs"
    const val KEY_TEXT = "text_lines"
    const val KEY_DELAY_SECONDS = "delay_seconds"
}
