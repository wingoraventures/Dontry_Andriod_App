package com.dontry.app
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth

class TryOnManager(private val context: Context) {

    private val TRYON_API_URL = "https://supercriminally-ununified-arnoldo.ngrok-free.dev"
    private val TRYON_HOST = "web-production-ca122.up.railway.app"

    private var cachedIp: String? = null
    private var cacheTime: Long = 0
    private val CACHE_TTL_MS = 10 * 60 * 1000L

    private fun resolveViaSystemDns(hostname: String): String? {
        return try {
            val addresses = okhttp3.Dns.SYSTEM.lookup(hostname)
            val ip = addresses.firstOrNull()?.hostAddress
            Log.d("Dontry_DNS", "✅ System DNS resolved: $ip")
            ip
        } catch (e: Exception) {
            Log.w("Dontry_DNS", "❌ System DNS failed: ${e.message}")
            null
        }
    }



    private suspend fun getFirebaseToken(): String? {
        return try {
            FirebaseAuth.getInstance().currentUser
                ?.getIdToken(false)
                ?.await()
                ?.token
        } catch (e: Exception) {
            Log.w("Dontry", "Token fetch failed: ${e.message}")
            null
        }
    }

    private fun resolveViaGoogleDoh(hostname: String): String? {
        return try {
            val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://8.8.8.8/resolve?name=$hostname&type=A")
                .header("Host", "dns.google")
                .build()
            val response = dnsClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            response.close()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val record = answers.getJSONObject(i)
                if (record.optInt("type", 0) == 1) {
                    val data = record.optString("data", "")
                    if (data.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        Log.d("Dontry_DNS", "✅ Google DoH resolved: $data")
                        return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("Dontry_DNS", "❌ Google DoH failed: ${e.message}")
            null
        }
    }

    private fun resolveViaCloudflareDoh(hostname: String): String? {
        return try {
            val dnsClient = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("Host", "cloudflare-dns.com")
                .header("Accept", "application/dns-json")
                .build()
            val response = dnsClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            response.close()
            val json = JSONObject(body)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val record = answers.getJSONObject(i)
                if (record.optInt("type", 0) == 1) {
                    val data = record.optString("data", "")
                    if (data.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        Log.d("Dontry_DNS", "✅ Cloudflare DoH resolved: $data")
                        return data
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w("Dontry_DNS", "❌ Cloudflare DoH failed: ${e.message}")
            null
        }
    }

    private fun resolveHostname(hostname: String): List<java.net.InetAddress> {
        val now = System.currentTimeMillis()
        if (cachedIp != null && now - cacheTime < CACHE_TTL_MS) {
            Log.d("Dontry_DNS", "📦 Using cached IP: $cachedIp")
            return listOf(java.net.InetAddress.getByName(cachedIp))
        }
        val resolvedIp =
            resolveViaSystemDns(hostname)
                ?: resolveViaGoogleDoh(hostname)
                ?: resolveViaCloudflareDoh(hostname)
        if (resolvedIp != null) {
            cachedIp = resolvedIp
            cacheTime = now
            return listOf(java.net.InetAddress.getByName(resolvedIp))
        }
        cachedIp = null
        cacheTime = 0
        throw java.net.UnknownHostException("All DNS methods failed for $hostname")
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                return if (hostname == TRYON_HOST) resolveHostname(hostname)
                else okhttp3.Dns.SYSTEM.lookup(hostname)
            }
        })
        .addInterceptor { chain ->
            val request = chain.request()
            var response: okhttp3.Response? = null
            var lastException: Exception? = null
            repeat(2) { attempt ->
                try {
                    response?.close()
                    response = chain.proceed(request)
                    return@addInterceptor response!!
                } catch (e: Exception) {
                    lastException = e
                    if (e is java.net.UnknownHostException) {
                        cachedIp = null
                        cacheTime = 0
                    }
                    if (attempt < 1) Thread.sleep(1500)
                }
            }
            response ?: throw lastException ?: Exception("Request failed")
        }
        .hostnameVerifier { hostname, session ->
            javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                .verify(hostname, session) || hostname == TRYON_HOST
        }
        .build()

    private fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) { false }
    }

    private suspend fun warmUpServer(): Boolean {
        return try {
            val request = Request.Builder().url("$TRYON_API_URL/health").get().build()
            val response = client.newCall(request).execute()
            response.close()
            Log.d("Dontry", "✅ Server warm-up success")
            true
        } catch (e: Exception) {
            Log.w("Dontry", "⚠️ Server warm-up failed: ${e.message}")
            false
        }
    }


    suspend fun checkImageWithGemini(
        screenshot: Bitmap,
        textNodes: List<String>
    ): ValidationResult {
        return withContext(Dispatchers.IO) {

            if (!isNetworkAvailable()) {
                return@withContext ValidationResult(
                    GeminiResult.Error("📵 No internet connection.\nPlease connect and try again."),
                    null, "", "", ""
                )
            }

            try {
                Log.d("Dontry", "📤 Sending to backend for validation...")
                val screenshotBytes = bitmapToBytes(screenshot, quality = 85)
                val textNodesJoined = textNodes.joinToString("\n").take(6000)

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "screenshot", "screenshot.jpg",
                        screenshotBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart("text_nodes", textNodesJoined)
                    .build()

                val token = getFirebaseToken()
                    ?: return@withContext ValidationResult(
                        GeminiResult.Error("🔒 Please log in again to continue."),
                        null, "", "", ""
                    )

                val request = Request.Builder()
                    .url("$TRYON_API_URL/validate")
                    .header("Authorization", "Bearer $token")
                    .header("X-Device-Model", android.os.Build.MODEL)
                    .header("X-Device-Manufacturer", android.os.Build.MANUFACTURER)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d("Dontry", "Validate response: $responseBody")

                if (!response.isSuccessful) {
                    return@withContext try {
                        val json = JSONObject(responseBody)
                        ValidationResult(
                            GeminiResult.Error(json.optString("message", "Unknown error")),
                            null, "", "", ""
                        )
                    } catch (e: Exception) {
                        ValidationResult(GeminiResult.Error("Server error (${response.code})"), null, "", "", "")
                    }
                }

                val json = JSONObject(responseBody)
                val result        = json.getString("result").trim().uppercase()
                val sessionId     = json.optString("session_id", null)
                val productTitle  = json.optString("product_title", "")
                val productBrand  = json.optString("product_brand", "")
                val garmentClass  = json.optString("garment_class", "")
                val validateTimeMs = json.optInt("validate_time_ms", 0)

                Log.d("Dontry", "Validation: [$result] title=$productTitle brand=$productBrand class=$garmentClass session=$sessionId")

                val geminiResult = when (result) {
                    "NO_GARMENT"      -> GeminiResult.NoClothing
                    "UNCLEAR_GARMENT" -> GeminiResult.UnclearGarment
                    "PARTIAL_GARMENT" -> GeminiResult.PartialGarment
                    "READY"           -> GeminiResult.HasClothing
                    "ERROR"           -> GeminiResult.Error(json.optString("message", "Validation service error"))
                    else -> {
                        Log.w("Dontry", "Unexpected result: $result")
                        GeminiResult.UnclearGarment
                    }
                }

                ValidationResult(geminiResult, sessionId, productTitle, productBrand, garmentClass, validateTimeMs)

            } catch (e: java.net.SocketTimeoutException) {
                ValidationResult(GeminiResult.Error("⏱️ Server took too long to respond.\nPlease try again."), null, "", "", "")
            } catch (e: java.net.UnknownHostException) {
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("model", android.os.Build.MODEL)
                    setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                    setCustomKey("error_type", "dns_failure")
                    recordException(e)
                }
                ValidationResult(GeminiResult.Error("🌐 Couldn't reach our servers.\nTry switching WiFi↔mobile data and retry."), null, "", "", "")
            } catch (e: Exception) {
                Log.e("Dontry", "Validate error: ${e.message}")
                ValidationResult(GeminiResult.Error("😕 Something went wrong while checking the image.\nPlease try again."), null, "", "", "")
            }
        }
    }

    suspend fun startTryOn(
        screenshot: Bitmap,
        sessionId: String?,
        garmentClass: String?,
        validateTimeMs: Int = 0,
        onProgress: (String) -> Unit
    ): TryOnResult {
        return withContext(Dispatchers.IO) {

            if (!isNetworkAvailable()) {
                return@withContext TryOnResult.Error(
                    "📵 No internet connection.\nPlease connect and try again."
                )
            }

            try {
                val looksManager = LooksManager(context)
                val photoPath = looksManager.getPhotoForTryOn()
                    ?: return@withContext TryOnResult.Error(
                        "📸 No profile photo found.\nPlease add your photo from the main screen first."
                    )

                val profileFile = File(photoPath)
                if (!profileFile.exists()) {
                    return@withContext TryOnResult.Error(
                        "📸 Your profile photo is missing.\nPlease re-upload your photo and try again."
                    )
                }

                val profileBitmap = BitmapFactory.decodeFile(photoPath)
                    ?: return@withContext TryOnResult.Error(
                        "📸 Could not read your profile photo.\nPlease re-upload your photo and try again."
                    )

                onProgress("Uploading images...")
                Log.d("Dontry", "📤 Sending to Try-On API...")

                val screenshotBytes = bitmapToBytes(screenshot, quality = 90)
                val profileBytes    = bitmapToBytes(profileBitmap, quality = 90)

                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "clothing_image", "clothing.jpg",
                        screenshotBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart(
                        "person_image", "person.jpg",
                        profileBytes.toRequestBody("image/jpeg".toMediaType())
                    )
                    .addFormDataPart("garment_description", "clothing item")
                    .addFormDataPart("category", categoryFromGarmentClass(garmentClass))

                if (!garmentClass.isNullOrEmpty()) {
                    bodyBuilder.addFormDataPart("garment_class", garmentClass)
                }

                // Forward session_id so backend links to the same analytics row
                if (!sessionId.isNullOrEmpty()) {
                    bodyBuilder.addFormDataPart("session_id", sessionId)
                }

                val token = getFirebaseToken()
                    ?: return@withContext TryOnResult.Error("🔒 Please log in again to continue.")

                val request = Request.Builder()
                    .url("$TRYON_API_URL/try-on")
                    .header("Authorization", "Bearer $token")
                    .post(bodyBuilder.build())
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                Log.d("Dontry", "Try-On API response (${response.code}): $responseBody")

                when (response.code) {
                    200, 201, 202 -> {
                        val json = JSONObject(responseBody)
                        val requestId = json.getString("request_id")
                        onProgress("Processing try-on...")
                        pollForResult(requestId, token, onProgress)
                    }
                    400 -> TryOnResult.Error(
                        "🖼️ The product image wasn't clear enough.\nTry scrolling to the main front photo of the clothing and try again."
                    )
                    413 -> TryOnResult.Error(
                        "📦 The image is too large to process.\nPlease try again with a different product photo."
                    )
                    422 -> TryOnResult.Error(
                        "🖼️ We couldn't read this image format.\nPlease try again on a different product page."
                    )
                    500 -> TryOnResult.Error(
                        "⚙️ Our server had trouble with this product image.\nTry a product page where the clothing is shown on a plain white background."
                    )
                    else -> TryOnResult.Error(
                        "😕 Something went wrong while starting the try-on.\nPlease try again in a moment."
                    )
                }

            } catch (e: java.net.SocketTimeoutException) {
                TryOnResult.Error("⏱️ Server took too long to respond.\nPlease check your connection and try again.")
            } catch (e: java.net.UnknownHostException) {
                TryOnResult.Error("🌐 Couldn't reach our servers.\nTry switching WiFi↔mobile data and retry.")
            } catch (e: Exception) {
                Log.e("Dontry", "Try-On error: ${e.message}")
                TryOnResult.Error("😕 Something unexpected happened.\nPlease try again.")
            }
        }
    }

    private fun categoryFromGarmentClass(garmentClass: String?): String = when (garmentClass) {
        "trousers", "shorts", "skirt" -> "lower_body"
        "short_sleeved_dress", "long_sleeved_dress",
        "vest_dress", "sling_dress" -> "dresses"
        else -> "upper_body" // shirts, outwear, vest, sling, or unknown/empty
    }


    private suspend fun pollForResult(
        requestId: String,
        token: String,
        onProgress: (String) -> Unit
    ): TryOnResult {
        var pollCount = 0
        val maxPolls = 60
        var consecutiveErrors = 0
        val maxConsecutiveErrors = 3

        while (pollCount < maxPolls) {
            try {
                kotlinx.coroutines.delay(2000)
                pollCount++
                Log.d("Dontry", "🔄 Poll #$pollCount for $requestId")
                onProgress("Processing... (${pollCount * 2}s)")

                val request = Request.Builder()
                    .url("$TRYON_API_URL/try-on/status/$requestId")
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                consecutiveErrors = 0

                when (response.code) {
                    200 -> {
                        val json = JSONObject(responseBody)
                        val imageUrl = when {
                            json.has("results") ->
                                json.getJSONObject("results").getString("try_on_image")
                            json.has("try_on_image") -> json.getString("try_on_image")
                            json.has("image_url")    -> json.getString("image_url")
                            else -> return TryOnResult.Error(
                                "😕 Try-on completed but we couldn't get the result image.\nPlease try again."
                            )
                        }
                        Log.d("Dontry", "✅ Try-On complete!")
                        return TryOnResult.Success(imageUrl)
                    }
                    202 -> continue
                    500 -> return TryOnResult.Error(
                        "⚙️ The server couldn't process this outfit.\nTry a product page where the clothing is shown clearly on a plain background."
                    )
                    else -> return TryOnResult.Error(
                        "😕 Something went wrong while getting your result.\nPlease try again."
                    )
                }

            } catch (e: java.net.SocketTimeoutException) {
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrors)
                    return TryOnResult.Error("⏱️ Lost connection while processing.\nPlease check your internet and try again.")
            } catch (e: java.net.UnknownHostException) {
                consecutiveErrors++
                cachedIp = null; cacheTime = 0
                if (consecutiveErrors >= maxConsecutiveErrors)
                    return TryOnResult.Error("🌐 Lost server connection while processing.\nTry switching WiFi↔mobile data and retry.")
            } catch (e: Exception) {
                consecutiveErrors++
                if (consecutiveErrors >= maxConsecutiveErrors)
                    return TryOnResult.Error("😕 We lost track of your try-on request.\nPlease try again.")
            }
        }

        return TryOnResult.Error(
            "⏱️ This is taking longer than usual.\nOur servers might be busy — please try again in a few minutes."
        )
    }

    private fun bitmapToBytes(bitmap: Bitmap, quality: Int = 85): ByteArray {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return outputStream.toByteArray()
    }
}

sealed class GeminiResult {
    object HasClothing    : GeminiResult()
    object NoClothing     : GeminiResult()
    object UnclearGarment : GeminiResult()
    object PartialGarment : GeminiResult()
    data class Error(val message: String) : GeminiResult()
}

data class ValidationResult(
    val geminiResult: GeminiResult,
    val sessionId: String?,
    val productTitle: String,
    val productBrand: String,
    val garmentClass: String,
    val validateTimeMs: Int = 0
)

sealed class TryOnResult {
    data class Success(val imageUrl: String) : TryOnResult()
    data class Error(val message: String)    : TryOnResult()
}

data class ProductInfo(val productName: String, val brand: String)