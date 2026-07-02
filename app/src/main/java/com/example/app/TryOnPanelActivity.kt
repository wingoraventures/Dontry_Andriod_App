package com.dontry.app

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class TryOnPanelActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tryon_panel)
        applyWindowInsets(findViewById(android.R.id.content))

        // Load user profile photo
        val prefs = getSharedPreferences("Dontry", MODE_PRIVATE)
        val photoUri = prefs.getString("profile_photo", null)
        photoUri?.let {
            val iv = findViewById<ImageView>(R.id.ivUserPhoto)
            iv.setImageURI(android.net.Uri.parse(it))
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
        }

        // Generate button
        findViewById<Button>(R.id.btnGenerate).setOnClickListener {
            // TODO: Connect to Vertex AI API here
            android.widget.Toast.makeText(
                this,
                "Generating try-on... ✨",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        // Close button
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }

        // Tap outside panel to close
        findViewById<android.view.View>(android.R.id.content).setOnClickListener {
            finish()
        }
    }
}