package com.dontry.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth

class PermissionActivity : BaseActivity() {

    private var hasProceeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)
        applyWindowInsets(findViewById(android.R.id.content))
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatusIcons()
        updateButtonText()
        if (hasOverlayPermission() && isAccessibilityServiceEnabled() && !hasProceeded) {
            hasProceeded = true
            proceedToApp()
        }
    }

    private fun setupClickListeners() {
        findViewById<LinearLayout>(R.id.rowOverlay).setOnClickListener {
            startActivity(Intent(this, OverlayGuideActivity::class.java))
        }

        findViewById<LinearLayout>(R.id.rowUsage).setOnClickListener {
            startActivity(Intent(this, AccessibilityGuideActivity::class.java))
        }

        findViewById<Button>(R.id.btnGrantAll).setOnClickListener {
            when {
                !hasOverlayPermission() -> startActivity(Intent(this, OverlayGuideActivity::class.java))
                !isAccessibilityServiceEnabled() -> startActivity(Intent(this, AccessibilityGuideActivity::class.java))
                else -> proceedToApp()
            }
        }

        findViewById<TextView>(R.id.restrictedHelpLink).setOnClickListener {
            openAppInfoSettings()
        }
    }

    private fun openAppInfoSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun updateButtonText() {
        val btn = findViewById<Button>(R.id.btnGrantAll)
        btn.text = when {
            !hasOverlayPermission() -> "Grant Overlay Permission"
            !isAccessibilityServiceEnabled() -> "Enable Accessibility Service"
            else -> "Continue"
        }
    }

    private fun updateStatusIcons() {
        findViewById<TextView>(R.id.overlayStatus).text =
            if (hasOverlayPermission()) "✓" else "›"

        findViewById<TextView>(R.id.usageStatus).text =
            if (isAccessibilityServiceEnabled()) "✓" else "›"

        val showRestrictedHelp = Build.VERSION.SDK_INT >= 33 && !isAccessibilityServiceEnabled()
        findViewById<TextView>(R.id.restrictedHelpLink).visibility =
            if (showRestrictedHelp) View.VISIBLE else View.GONE
    }

    private fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(this)

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val shortFormat = "$packageName/.TryVueAccessibilityService"
        val fullFormat = "$packageName/${packageName}.TryVueAccessibilityService"

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        splitter.forEach { name ->
            if (name.equals(shortFormat, ignoreCase = true) ||
                name.equals(fullFormat, ignoreCase = true)
            ) return true
        }
        return false
    }

    private fun proceedToApp() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        finish()
    }
}