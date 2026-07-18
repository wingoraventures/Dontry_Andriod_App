package com.dontry.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import okhttp3.MediaType.Companion.toMediaTypeOrNull

class ResultActivity : BaseActivity() {

    private var resultBitmap: Bitmap? = null
    private var imagePath: String = ""
    private var sharedImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        applyWindowInsets(findViewById(android.R.id.content))

        imagePath = intent.getStringExtra("result_image_path") ?: ""

        val productName  = intent.getStringExtra("product_name")?.takeIf { it.isNotBlank() } ?: "Unknown"
        val productBrand = intent.getStringExtra("product_brand")?.takeIf { it.isNotBlank() } ?: "Unknown"

        val db  = FirebaseFirestore.getInstance()
        val uid = FirebaseAuth.getInstance().currentUser?.uid

        val ivResult       = findViewById<ImageView>(R.id.ivResult)
        val tvStatus       = findViewById<TextView>(R.id.tvStatus)
        val tvSaveLabel    = findViewById<TextView>(R.id.tvSaveLabel)
        val btnSaveToLooks = findViewById<LinearLayout>(R.id.btnSaveToLooks)
        val btnDownload    = findViewById<LinearLayout>(R.id.btnDownload)
        val btnShare       = findViewById<LinearLayout>(R.id.btnShare)
        val btnCloseBottom = findViewById<LinearLayout>(R.id.btnCloseBottom)

        val llBuyNowHeader   = findViewById<LinearLayout>(R.id.llBuyNowHeader)
        val feedbackSection  = findViewById<LinearLayout>(R.id.feedbackSection)
        val btnFeedbackYes   = findViewById<TextView>(R.id.btnFeedbackYes)
        val btnFeedbackNo    = findViewById<TextView>(R.id.btnFeedbackNo)
        val loveSection      = findViewById<LinearLayout>(R.id.loveSection)
        val reasonSection    = findViewById<LinearLayout>(R.id.reasonSection)
        val feedbackDone     = findViewById<LinearLayout>(R.id.feedbackDone)
        val tvFeedbackThanks = findViewById<TextView>(R.id.tvFeedbackThanks)

        tvStatus.text = "Loading..."


        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bitmap = BitmapFactory.decodeFile(imagePath)


                var cachedShareUri: Uri? = null
                if (bitmap != null) {
                    try {
                        val cacheFile = File(cacheDir, "tryvue_share_latest.jpg")
                        FileOutputStream(cacheFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                        }
                        cachedShareUri = FileProvider.getUriForFile(
                            this@ResultActivity,
                            "${packageName}.fileprovider",
                            cacheFile
                        )
                    } catch (e: Exception) {
                        Log.e("ResultActivity", "Share pre-cache failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    resultBitmap = bitmap
                    sharedImageUri = cachedShareUri
                    if (bitmap != null) {
                        ivResult.setImageBitmap(bitmap)
                        tvStatus.text = "Try-On Complete ✓"
                    } else {
                        tvStatus.text = "Failed to load image"
                        btnSaveToLooks.isEnabled = false
                        btnShare.isEnabled = false
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                }
            }
        }

        // ── Save to Looks ─────────────────────────────────────────
        btnSaveToLooks.setOnClickListener {
            val bitmap = resultBitmap ?: run {
                Toast.makeText(this, "Image not ready", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvSaveLabel.text = "Saving..."
            btnSaveToLooks.isEnabled = false

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val looksManager = LooksManager(this@ResultActivity)
                    looksManager.saveLook(bitmap)
                    withContext(Dispatchers.Main) {
                        tvSaveLabel.text = "Saved ✓"
                        Toast.makeText(this@ResultActivity, "Saved to My Looks!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvSaveLabel.text = "Save Look"
                        btnSaveToLooks.isEnabled = true
                        Toast.makeText(this@ResultActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ── Download ──────────────────────────────────────────────
        btnDownload.setOnClickListener { saveToGallery() }

        // ── Share ─────────────────────────────────────────────────
        btnShare.setOnClickListener { shareResult() }

        // ── Close ─────────────────────────────────────────────────
        btnCloseBottom.setOnClickListener { finish() }

        // ── Feedback ──────────────────────────────────────────────
        llBuyNowHeader.setOnClickListener {
            feedbackSection.visibility =
                if (feedbackSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnFeedbackYes.setOnClickListener {
            btnFeedbackYes.setBackgroundColor(Color.parseColor("#E8622A"))
            btnFeedbackYes.setTextColor(Color.WHITE)
            btnFeedbackNo.setBackgroundColor(Color.parseColor("#F2F2F2"))
            btnFeedbackNo.setTextColor(Color.parseColor("#333333"))
            loveSection.visibility   = View.VISIBLE
            reasonSection.visibility = View.GONE

            listOf(R.id.btnLovePrice, R.id.btnLoveDesign, R.id.btnLoveFit, R.id.btnLoveQuality)
                .forEach { id ->
                    findViewById<TextView>(id).setOnClickListener { btn ->
                        val reason = (btn as TextView).text.toString()
                        saveFeedbackToFirestore(db, uid, productName, productBrand, "Yes", reason)
                        loveSection.visibility  = View.GONE
                        feedbackDone.visibility = View.VISIBLE
                        tvFeedbackThanks.text   = "Thanks for your feedback! 🎉"
                    }
                }
        }

        btnFeedbackNo.setOnClickListener {
            btnFeedbackNo.setBackgroundColor(Color.parseColor("#E8622A"))
            btnFeedbackNo.setTextColor(Color.WHITE)
            btnFeedbackYes.setBackgroundColor(Color.parseColor("#F2F2F2"))
            btnFeedbackYes.setTextColor(Color.parseColor("#333333"))
            reasonSection.visibility = View.VISIBLE
            loveSection.visibility   = View.GONE

            listOf(R.id.btnReasonPrice, R.id.btnReasonDesign).forEach { id ->
                findViewById<TextView>(id).setOnClickListener { btn ->
                    val reason = (btn as TextView).text.toString()
                    saveFeedbackToFirestore(db, uid, productName, productBrand, "No", reason)
                    reasonSection.visibility = View.GONE
                    feedbackDone.visibility  = View.VISIBLE
                    tvFeedbackThanks.text    = "Got it! We'll improve 💪"
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    private fun saveFeedbackToFirestore(
        db: FirebaseFirestore,
        uid: String?,
        productName: String,
        productBrand: String,
        decision: String,
        reason: String
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val idToken = FirebaseAuth.getInstance().currentUser
                    ?.getIdToken(false)?.await()?.token ?: return@launch

                val json = org.json.JSONObject().apply {
                    put("product_brand", productBrand)
                    put("product_name", productName)
                    put("decision", decision)
                    put("reason", reason)
                }
                val client = okhttp3.OkHttpClient()
                val body = okhttp3.RequestBody.create(
                    "application/json".toMediaTypeOrNull(), json.toString()
                )
                val request = okhttp3.Request.Builder()
                    .url("${Constants.API_BASE_URL}/feedback")
                    .header("Authorization", "Bearer $idToken")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.d("Dontry", "✅ Feedback saved to backend")
                    } else {
                        Log.e("Dontry", "❌ Feedback save failed: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                Log.e("Dontry", "❌ Feedback save error: ${e.message}")
            }
        }
    }

    // ── Share — uses pre-cached URI, opens instantly ──────────────
    private fun shareResult() {
        val uri = sharedImageUri ?: run {
            Toast.makeText(this, "Image not ready to share", Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "Check out my virtual try-on with Dontry! 👗")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share your look via…"))
    }

    // ── Download ──────────────────────────────────────────────────
    private fun saveToGallery() {
        val bitmap = resultBitmap ?: run {
            Toast.makeText(this, "Image not ready", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val filename = "TryVue_${System.currentTimeMillis()}.png"
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_PICTURES + "/TryVue"
                    )
                }
                val uri = contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ResultActivity, "Saved to Gallery ✓", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ResultActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingService.onResultClosed?.invoke()
        FloatingService.onResultClosed = null
    }
}