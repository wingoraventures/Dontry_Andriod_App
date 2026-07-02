package com.dontry.app
import androidx.exifinterface.media.ExifInterface
import android.graphics.Matrix
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import java.io.File
import java.io.FileOutputStream

class OnboardingActivity : BaseActivity() {

    private val PICK_IMAGE = 101
    private lateinit var ivProfile: ImageView
    private lateinit var layoutNoPhoto: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val photoFile = File(filesDir, "profile_photo.jpg")
        if (photoFile.exists()) {
            getSharedPreferences("Dontry", MODE_PRIVATE).edit()
                .putString("profile_photo_path", photoFile.absolutePath)
                .apply()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_onboarding)
        applyWindowInsets(findViewById(android.R.id.content))

        ivProfile = findViewById(R.id.ivProfilePhoto)
        layoutNoPhoto = findViewById(R.id.layoutNoPhoto)

        findViewById<Button>(R.id.btnChooseGallery).setOnClickListener {
            val intent = Intent(
                Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            startActivityForResult(intent, PICK_IMAGE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_IMAGE && resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = data?.data
            imageUri?.let { uri ->
                val savedPath = copyImageToAppStorage(uri)
                if (savedPath != null) {
                    getSharedPreferences("Dontry", MODE_PRIVATE)
                        .edit()
                        .putString("profile_photo_path", savedPath)
                        .apply()

                    ivProfile.setImageBitmap(BitmapFactory.decodeFile(savedPath))
                    ivProfile.scaleType = ImageView.ScaleType.CENTER_CROP
                    ivProfile.visibility = View.VISIBLE
                    layoutNoPhoto.visibility = View.GONE

                    android.widget.Toast.makeText(
                        this, "✅ Photo saved!", android.widget.Toast.LENGTH_SHORT
                    ).show()

                    // Go back to MainActivity after saving
                    ivProfile.postDelayed({ goToHome() }, 1000)
                } else {
                    android.widget.Toast.makeText(
                        this, "❌ Failed to save photo", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun copyImageToAppStorage(uri: Uri): String? {
        return try {
            // 1. Read EXIF — safe null check
            val orientation = contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            // 2. Decode pixels — safe null check
            val bitmap = contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return null  // corrupt image → exit safely, no crash

            // 3. Rotate
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )

            // 4. Scale
            val maxSize = 1024
            val scaled = if (rotated.width > maxSize || rotated.height > maxSize) {
                val ratio = minOf(
                    maxSize.toFloat() / rotated.width,
                    maxSize.toFloat() / rotated.height
                )
                Bitmap.createScaledBitmap(
                    rotated,
                    (rotated.width * ratio).toInt(),
                    (rotated.height * ratio).toInt(),
                    true
                )
            } else rotated

            // 5. Save to temp first → rename on success
            val tempFile = File(filesDir, "profile_photo_temp.jpg")
            FileOutputStream(tempFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            val finalFile = File(filesDir, "profile_photo.jpg")
            tempFile.renameTo(finalFile)

            finalFile.absolutePath

        } catch (e: Exception) {
            null
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}