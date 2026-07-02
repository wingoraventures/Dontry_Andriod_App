package com.dontry.app

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.io.FileOutputStream

class MainActivity : BaseActivity() {

    private lateinit var btnStart: Button


    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        handleSelectedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets(findViewById(android.R.id.content))

        btnStart = findViewById(R.id.btnStart)

        refreshProfilePhoto()

        findViewById<LinearLayout>(R.id.llProfileRow).setOnClickListener {
            pickImage.launch("image/*")
        }

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            FirebaseAuth.getInstance().signOut()

            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(getString(R.string.default_web_client_id))
                .requestEmail()
                .build()
            GoogleSignIn.getClient(this, gso).signOut()

            getSharedPreferences("Dontry", MODE_PRIVATE).edit()
                .remove("selected_look_id")
                .remove("selected_look_path")
                .apply()
            stopService(Intent(this, FloatingService::class.java))

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        btnStart.setOnClickListener {
            handleStartStop()
        }
    }

    // ── New image handler — safe on all Android versions ──
    private fun handleSelectedImage(uri: android.net.Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri)
            var bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            if (bitmap == null) {
                Toast.makeText(this, "❌ Could not read image", Toast.LENGTH_SHORT).show()
                return
            }

            // Fix EXIF orientation
            val exifStream = contentResolver.openInputStream(uri)
            val exif = ExifInterface(exifStream!!)
            exifStream.close()
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            bitmap = android.graphics.Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            val file = File(filesDir, "profile_photo.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }

            getSharedPreferences("Dontry", MODE_PRIVATE).edit()
                .putString("profile_photo_path", file.absolutePath)
                .apply()

            refreshProfilePhoto()
            Toast.makeText(this, "✅ Photo updated!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "❌ Failed to save photo", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
        refreshProfilePhoto()
    }

    private fun refreshProfilePhoto() {
        val prefs = getSharedPreferences("Dontry", MODE_PRIVATE)
        val photoPath = prefs.getString("profile_photo_path", null)
        val iv = findViewById<ImageView>(R.id.ivUserAvatar)
        photoPath?.let {
            val file = File(it)
            if (file.exists()) {
                iv.setImageBitmap(BitmapFactory.decodeFile(it))
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
            }
        }
    }

    private fun updateButtonState() {
        val isRunning = FloatingService.instance != null
        btnStart.text = if (isRunning) "Stop Try-On" else "Start Try-On"
    }

    // ── Notification permission — Android 13+ only ──
    private fun askNotificationPermission(onGranted: () -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                android.app.AlertDialog.Builder(
                    this, android.R.style.Theme_Material_Light_Dialog_Alert
                )
                    .setTitle("Allow notifications")
                    .setMessage(
                        "To show your try-on progress while you browse, " +
                                "please allow notifications."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        requestPermissions(
                            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                            101
                        )
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        onGranted()
                    }
                    .show()
                return
            }
        }
        onGranted()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            startForegroundService(Intent(this, FloatingService::class.java))
            Toast.makeText(
                this,
                "✅ Ready! Open any fashion app to start Try-On.",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    // ── Start / Stop service ──
    private fun handleStartStop() {
        val accService = TryVueAccessibilityService.instance
        val floatingService = FloatingService.instance

        if (floatingService != null) {
            stopService(Intent(this, FloatingService::class.java))
            btnStart.postDelayed({ updateButtonState() }, 300)
            Toast.makeText(this, "⏹ Try-On stopped.", Toast.LENGTH_SHORT).show()
        } else {
            if (accService == null) {
                Toast.makeText(
                    this,
                    "⚠️ Please enable Dontry in Accessibility Settings first",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Step 1 — check battery optimisation
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                android.app.AlertDialog.Builder(
                    this, android.R.style.Theme_Material_Light_Dialog_Alert
                )
                    .setTitle("Keep Try-On running in background")
                    .setMessage(
                        "When you leave the shopping app, your try-on continues " +
                                "processing in the background.\n\n" +
                                "To prevent your phone from stopping it midway, please tap " +
                                "Allow on the next screen."
                    )
                    .setPositiveButton("Allow") { _, _ ->
                        startActivity(
                            Intent(
                                android.provider.Settings
                                    .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:$packageName")
                            )
                        )
                    }
                    .setNegativeButton("Not now") { _, _ ->
                        Toast.makeText(
                            this,
                            "Tip: Allow battery permission next time for best results",
                            Toast.LENGTH_LONG
                        ).show()
                        startForegroundService(Intent(this, FloatingService::class.java))
                        updateButtonState()
                        finish()
                    }
                    .show()
                return
            }

            // Step 2 — battery whitelisted, check notification permission then start
            askNotificationPermission {
                startForegroundService(Intent(this, FloatingService::class.java))
                Toast.makeText(
                    this,
                    "✅ Ready! Open any fashion app to start Try-On.",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}