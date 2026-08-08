package com.dontry.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.Purchase as BillingPurchase
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.android.billingclient.api.PendingPurchasesParams

class PaymentActivity : BaseActivity(), PurchasesUpdatedListener {

    private val TRYON_API_URL = Constants.API_BASE_URL

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var planId: String
    private lateinit var billingClient: BillingClient
    private var productDetails: ProductDetails? = null

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

    private val productIdMap = mapOf(
        "test"      to "tryon_test",
        "1tryon"    to "tryon_1",
        "5tryon"    to "tryon_5",
        "10tryon"   to "tryon_10",
        "50tryon"   to "tryon_50",
        "100tryon"  to "tryon_100",
        "1000tryon" to "tryon_1000"
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
        findViewById<TextView>(R.id.tvPlanName).text = name
        findViewById<TextView>(R.id.tvPlanDetails).text = "$tryons • $validity"

        val btnPay = findViewById<Button>(R.id.btnPayNow)
        btnPay.isEnabled = false
        btnPay.text = "Loading..."

        billingClient = BillingClient.newBuilder(this)
            .setListener(this)
            .enablePendingPurchases(
                com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductAndShowPrice()
                } else {
                    Toast.makeText(this@PaymentActivity, "Billing unavailable", Toast.LENGTH_LONG).show()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    override fun onResume() {
        super.onResume()
        if (::billingClient.isInitialized && billingClient.isReady) {
            checkExistingPurchase()
        }
    }

    private fun showVerifyOverlay() {
        findViewById<LinearLayout>(R.id.verifyOverlay).visibility = View.VISIBLE
    }

    private fun hideVerifyOverlay() {
        findViewById<LinearLayout>(R.id.verifyOverlay).visibility = View.GONE
    }

    private fun queryProductAndShowPrice() {
        val productId = productIdMap[planId] ?: run {
            Toast.makeText(this, "Plan not configured", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(product))
            .build()

        billingClient.queryProductDetailsAsync(params, object : com.android.billingclient.api.ProductDetailsResponseListener {
            override fun onProductDetailsResponse(
                result: BillingResult,
                queryProductDetailsResult: com.android.billingclient.api.QueryProductDetailsResult
            ) {
                runOnUiThread {
                    val details = queryProductDetailsResult.productDetailsList.firstOrNull()
                    if (result.responseCode != BillingClient.BillingResponseCode.OK || details == null) {
                        Toast.makeText(this@PaymentActivity, "Failed to load plan price", Toast.LENGTH_LONG).show()
                        finish()
                        return@runOnUiThread
                    }
                    productDetails = details
                    val price = details.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                    findViewById<TextView>(R.id.tvPlanPrice).text = price

                    val btnPay = findViewById<Button>(R.id.btnPayNow)
                    btnPay.isEnabled = true
                    btnPay.text = "Pay Now"
                    btnPay.setOnClickListener { launchPurchaseFlow() }
                }
            }
        })
    }

    private fun launchPurchaseFlow() {
        val details = productDetails ?: return

        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()

        billingClient.launchBillingFlow(this, flowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<BillingPurchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { handlePurchase(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Toast.makeText(this, "Payment cancelled", Toast.LENGTH_SHORT).show()
            }
            else -> {
                Toast.makeText(this, "Payment failed: ${result.debugMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkExistingPurchase() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { p ->
                    if (p.purchaseState == BillingPurchase.PurchaseState.PURCHASED && p.products.contains(productIdMap[planId])) {
                        handlePurchase(p)
                    }
                }
            }
        }
    }

    private fun handlePurchase(purchase: BillingPurchase) {
        if (purchase.purchaseState != BillingPurchase.PurchaseState.PURCHASED) {
            return
        }
        showVerifyOverlay()
        verifyPurchaseOnBackend(purchase.purchaseToken)
    }

    private fun verifyPurchaseOnBackend(purchaseToken: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("plan_id", planId)
                    put("purchase_token", purchaseToken)
                }.toString().toRequestBody("application/json".toMediaType())

                val token = FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token ?: ""

                val request = Request.Builder()
                    .url("$TRYON_API_URL/payment/verify-play")
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

    override fun onDestroy() {
        super.onDestroy()
        if (::billingClient.isInitialized) billingClient.endConnection()
    }
}