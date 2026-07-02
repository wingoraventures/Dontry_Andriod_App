package com.dontry.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class OverlayGuideActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_guide)
        applyWindowInsets(findViewById(android.R.id.content))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            finish()
        }
    }
}