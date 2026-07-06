package com.dontry.app

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class SubscriptionActivity : BaseActivity() {

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
        setContentView(R.layout.activity_subscription)
        applyWindowInsets(findViewById(android.R.id.content))

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<LinearLayout>(R.id.planTest).setOnClickListener { goToPayment("test") }
        findViewById<LinearLayout>(R.id.plan1).setOnClickListener { goToPayment("1tryon") }
        findViewById<LinearLayout>(R.id.plan5).setOnClickListener { goToPayment("5tryon") }
        findViewById<LinearLayout>(R.id.plan10).setOnClickListener { goToPayment("10tryon") }
        findViewById<LinearLayout>(R.id.plan50).setOnClickListener { goToPayment("50tryon") }
        findViewById<LinearLayout>(R.id.plan100).setOnClickListener { goToPayment("100tryon") }
        findViewById<LinearLayout>(R.id.plan1000).setOnClickListener { goToPayment("1000tryon") }
    }

    override fun onResume() {
        super.onResume()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        FirebaseFirestore.getInstance().collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val count = doc.getLong("tryonsRemaining") ?: 0
                val total = doc.getLong("tryonsTotal") ?: count
                findViewById<TextView>(R.id.tvCurrentTryons).text = count.toString()
                findViewById<TextView>(R.id.tvTotalTryons).text = "of $total total"

                // ── Active plan card ──
                val activePlan = doc.getString("tryonPlan")
                val expiry = doc.getLong("activePlanExpiry")

                val planCard = findViewById<LinearLayout>(R.id.activePlanCard)
                if (!activePlan.isNullOrEmpty()) {
                    planCard.visibility = android.view.View.VISIBLE
                    // Look up friendly name from planDisplay map
                    val planDisplayName = planDisplay[activePlan]?.first ?: activePlan
                    findViewById<TextView>(R.id.tvActivePlanName).text = planDisplayName

                    val expiryText = if (expiry != null && expiry > 0) {
                        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        "Valid until ${sdf.format(Date(expiry))}"
                    } else {
                        "No expiry"
                    }
                    findViewById<TextView>(R.id.tvActivePlanExpiry).text = expiryText
                } else {
                    planCard.visibility = android.view.View.GONE
                }
            }

        fetchPurchaseHistory()
    }

    private fun goToPayment(planId: String) {
        val intent = Intent(this, PaymentActivity::class.java)
        intent.putExtra("planId", planId)
        startActivity(intent)
    }

    private fun fetchPurchaseHistory() {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(true).addOnSuccessListener { result ->
            val token = result.token
            val request = Request.Builder()
                .url("${Constants.API_BASE_URL}/payment/history")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(this@SubscriptionActivity, "Failed to load history", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: return
                    val json = JSONObject(body)
                    if (!json.optBoolean("success")) return

                    val arr = json.getJSONArray("history")
                    val list = mutableListOf<Purchase>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(
                            Purchase(
                                planId = o.optString("plan_id"),
                                amount = o.optInt("amount"),
                                tryonsCredited = o.optInt("tryons_credited"),
                                purchasedAt = o.optLong("purchased_at")
                            )
                        )
                    }

                    runOnUiThread {
                        val rv = findViewById<NonScrollableRecyclerView>(R.id.rvPurchaseHistory)
                        val emptyView = findViewById<TextView>(R.id.tvNoHistory)
                        if (list.isEmpty()) {
                            rv.visibility = android.view.View.GONE
                            emptyView.visibility = android.view.View.VISIBLE
                        } else {
                            rv.visibility = android.view.View.VISIBLE
                            emptyView.visibility = android.view.View.GONE
                            rv.layoutManager = LinearLayoutManager(this@SubscriptionActivity)
                            rv.adapter = PurchaseHistoryAdapter(list)
                        }
                    }
                }
            })
        }
    }
}