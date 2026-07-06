package com.dontry.app
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

object ProfilePhotoUploader {

    private const val TAG = "ProfilePhotoUploader"
    private val UPLOAD_URL = "${Constants.API_BASE_URL}/profile-photo"

    private val client = OkHttpClient()

    fun upload(file: File, onDone: (success: Boolean, url: String?) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onDone(false, null)
            return
        }

        user.getIdToken(false).addOnSuccessListener { result ->
            val token = result.token
            if (token == null) {
                onDone(false, null)
                return@addOnSuccessListener
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "photo",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                )
                .build()

            val request = Request.Builder()
                .url(UPLOAD_URL)
                .addHeader("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e(TAG, "Upload failed: ${e.message}")
                    onDone(false, null)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            Log.e(TAG, "Upload failed: ${it.code}")
                            onDone(false, null)
                            return
                        }
                        try {
                            val json = JSONObject(it.body?.string() ?: "")
                            val url = json.optString("url", null)
                            onDone(json.optBoolean("success", false), url)
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse error: ${e.message}")
                            onDone(false, null)
                        }
                    }
                }
            })
        }.addOnFailureListener {
            Log.e(TAG, "Failed to get ID token: ${it.message}")
            onDone(false, null)
        }
    }
}