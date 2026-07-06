package com.dontry.app

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.razorpay.Checkout
import com.razorpay.PaymentData
import com.razorpay.PaymentResultWithDataListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await

class PaymentActivity : BaseActivity(), PaymentResultWithDataListener {

    private val TRYON_API_URL = Constants.API_BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var planId: String
    private var orderId: String = ""
    private var amount: Int = 0
    private var keyId: String = ""

    private val planDisplay = mapOf(
        "test"           to Triple("Test Plan", "5 tryons", "No expiry"),
        "Free Credits"   to Triple("Free Credits", "5 tryons", "No expiry"),
        "1tryon"    to Triple("Starter", "1 tryon", "Valid 30 days"),
        "5tryon"    to Triple("Basic", "5 tryons", "Valid 30 days"),
        "10tryon"   to Triple("Popular", "10 tryons", "Valid 30 days"),
        "50tryon"   to Triple("Value", "50 tryons", "Valid 30 days"),
        "100tryon"  to Triple("Pro", "100 tryons", "Valid 60 days"),
        "1000tryon" to Triple("Ultimate", "1000 tryons", "Valid 1 year")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)
        applyWindowInsets(findViewById(android.R.id.content))

        planId = intent.getStringExtra("planId") ?: run {
            Toast.makeText(this, "Invalid plan", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        val (name, tryons, validity) = planDisplay[planId] ?: Triple(planId, "", "")
        findViewById<android.widget.TextView>(R.id.tvPlanName).text = name
        findViewById<android.widget.TextView>(R.id.tvPlanDetails).text = "$tryons • $validity"

        Checkout.preload(applicationContext)
        createOrder()
    }

    private fun showVerifyOverlay() {
        findViewById<LinearLayout>(R.id.verifyOverlay).visibility = View.VISIBLE
    }

    private fun hideVerifyOverlay() {
        findViewById<LinearLayout>(R.id.verifyOverlay).visibility = View.GONE
    }

    private fun createOrder() {
        val btnPay = findViewById<android.widget.Button>(R.id.btnPayNow)
        btnPay.isEnabled = false
        btnPay.text = "Loading..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("plan_id", planId) }
                    .toString().toRequestBody("application/json".toMediaType())

                val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
                    ?: run {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@PaymentActivity, "Please log in again", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        return@launch
                    }

                val request = Request.Builder()
                    .url("$TRYON_API_URL/payment/create-order")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful && json.optBoolean("success")) {
                        orderId = json.getString("order_id")
                        amount  = json.getInt("amount")
                        keyId   = json.getString("key_id")

                        findViewById<android.widget.TextView>(R.id.tvPlanPrice).text = "₹${amount / 100}"
                        btnPay.isEnabled = true
                        btnPay.text = "Pay Now"
                        btnPay.setOnClickListener { startCheckout() }
                    } else {
                        Toast.makeText(this@PaymentActivity,
                            json.optString("message", "Failed to create order"), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PaymentActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun startCheckout() {
        val checkout = Checkout()
        checkout.setKeyID(keyId)

        val options = JSONObject().apply {
            put("name", "Dontry")
            put("description", "Try-On Plan Purchase")
            put("order_id", orderId)
            put("currency", "INR")
            put("amount", amount)
            put("prefill", JSONObject().apply {
                put("email", FirebaseAuth.getInstance().currentUser?.email ?: "")
            })
        }

        try {
            checkout.open(this, options)
        } catch (e: Exception) {
            Toast.makeText(this, "Error starting payment: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onPaymentSuccess(razorpayPaymentId: String?, data: PaymentData?) {
        val paymentId = razorpayPaymentId ?: return
        val orderIdFromResp = data?.orderId ?: orderId
        val signature = data?.signature ?: run {
            Toast.makeText(this, "Missing payment signature", Toast.LENGTH_LONG).show()
            return
        }

        // Show loading overlay immediately — Razorpay sheet closed, verify API call about to start
        showVerifyOverlay()

        verifyPayment(orderIdFromResp, paymentId, signature)
    }

    override fun onPaymentError(code: Int, description: String?, response: PaymentData?) {
        Toast.makeText(this, "Payment failed: $description", Toast.LENGTH_LONG).show()
    }

    private fun verifyPayment(razorpayOrderId: String, razorpayPaymentId: String, razorpaySignature: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("plan_id", planId)
                    put("razorpay_order_id", razorpayOrderId)
                    put("razorpay_payment_id", razorpayPaymentId)
                    put("razorpay_signature", razorpaySignature)
                }.toString().toRequestBody("application/json".toMediaType())

                val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: ""

                val request = Request.Builder()
                    .url("$TRYON_API_URL/payment/verify")
                    .header("Authorization", "Bearer $token")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val json = JSONObject(response.body?.string() ?: "")

                withContext(Dispatchers.Main) {
                    hideVerifyOverlay()
                    if (response.isSuccessful && json.optBoolean("success")) {
                        Toast.makeText(this@PaymentActivity, "Payment successful! Tryons credited.", Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this@PaymentActivity,
                            json.optString("message", "Verification failed"), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideVerifyOverlay()
                    Toast.makeText(this@PaymentActivity, "Verify error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}