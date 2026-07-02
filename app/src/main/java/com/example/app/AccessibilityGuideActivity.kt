package com.dontry.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView

class AccessibilityGuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accessibility_guide)
        applyWindowInsets(findViewById(android.R.id.content))

        if (Build.VERSION.SDK_INT >= 33) {
            findViewById<TextView>(R.id.restrictedNote).visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                val bundle = Bundle()
                val componentName = "$packageName/.TryVueAccessibilityService"
                bundle.putString(":settings:fragment_args_key", componentName)
                intent.putExtra(":settings:show_fragment_args", bundle)
                intent.putExtra(":settings:fragment_args_key", componentName)
                startActivity(intent)
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            finish()
        }
    }
}