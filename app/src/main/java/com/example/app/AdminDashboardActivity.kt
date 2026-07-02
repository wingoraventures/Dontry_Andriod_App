package com.dontry.app

import android.os.Bundle
import android.view.View
import android.widget.*

import com.google.firebase.firestore.FirebaseFirestore

class AdminDashboardActivity : BaseActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_dashboard)
        applyWindowInsets(findViewById(android.R.id.content))

        val btnClose          = findViewById<TextView>(R.id.btnAdminClose)
        val tabUsers          = findViewById<TextView>(R.id.tabUsers)
        val tabFeedback       = findViewById<TextView>(R.id.tabFeedback)
        val usersSection      = findViewById<LinearLayout>(R.id.usersSection)
        val feedbackSection   = findViewById<LinearLayout>(R.id.feedbackSection)
        val tvUserCount       = findViewById<TextView>(R.id.tvUserCount)
        val tvFeedbackCount   = findViewById<TextView>(R.id.tvFeedbackCount)
        val usersTableBody    = findViewById<LinearLayout>(R.id.usersTableBody)
        val feedbackTableBody = findViewById<LinearLayout>(R.id.feedbackTableBody)
        val progressUsers     = findViewById<ProgressBar>(R.id.progressUsers)
        val progressFeedback  = findViewById<ProgressBar>(R.id.progressFeedback)

        btnClose.setOnClickListener { finish() }

        // ── Tab switching ──────────────────────────────────────────
        fun selectTab(isUsers: Boolean) {
            if (isUsers) {
                tabUsers.setBackgroundColor(android.graphics.Color.parseColor("#E8622A"))
                tabUsers.setTextColor(android.graphics.Color.WHITE)
                tabFeedback.setBackgroundColor(android.graphics.Color.parseColor("#F2F2F2"))
                tabFeedback.setTextColor(android.graphics.Color.parseColor("#333333"))
                usersSection.visibility    = View.VISIBLE
                feedbackSection.visibility = View.GONE
            } else {
                tabFeedback.setBackgroundColor(android.graphics.Color.parseColor("#E8622A"))
                tabFeedback.setTextColor(android.graphics.Color.WHITE)
                tabUsers.setBackgroundColor(android.graphics.Color.parseColor("#F2F2F2"))
                tabUsers.setTextColor(android.graphics.Color.parseColor("#333333"))
                feedbackSection.visibility = View.VISIBLE
                usersSection.visibility    = View.GONE
            }
        }

        tabUsers.setOnClickListener    { selectTab(true)  }
        tabFeedback.setOnClickListener { selectTab(false) }

        // ── Load Users ─────────────────────────────────────────────
        progressUsers.visibility = View.VISIBLE
        db.collection("users").get()
            .addOnSuccessListener { usersSnap ->
                progressUsers.visibility = View.GONE
                tvUserCount.text = "${usersSnap.size()} Users"
                usersTableBody.removeAllViews()

                usersSnap.documents.forEachIndexed { index, userDoc ->
                    val name = userDoc.getString("name") ?: "—"

                    // FIX 2: check "contact" first, fallback to "email"
                    val contact = userDoc.getString("contact")
                        ?: userDoc.getString("email")
                        ?: "—"

                    val row = buildTableRow(
                        index    = index + 1,
                        name     = name,
                        contact  = contact,
                        rowIndex = index
                    )
                    usersTableBody.addView(row)

                    // Load feedback count per user async
                    db.collection("users").document(userDoc.id)
                        .collection("feedback").get()
                        .addOnSuccessListener { fbSnap ->
                            val countView = row.findViewWithTag<TextView>("col3_${index}")
                            countView?.text = "${fbSnap.size()} feedbacks"
                        }
                }
            }
            .addOnFailureListener {
                progressUsers.visibility = View.GONE
                Toast.makeText(this, "Failed to load users: ${it.message}", Toast.LENGTH_SHORT).show()
            }

        // ── Load All Feedback ──────────────────────────────────────
        progressFeedback.visibility = View.VISIBLE
        val allFeedback = mutableListOf<Map<String, Any>>()

        db.collection("users").get()
            .addOnSuccessListener { usersSnap ->
                val userDocs = usersSnap.documents
                if (userDocs.isEmpty()) {
                    progressFeedback.visibility = View.GONE
                    tvFeedbackCount.text = "0 Feedback"
                    return@addOnSuccessListener
                }

                var loadedCount = 0
                userDocs.forEach { userDoc ->
                    val userName = userDoc.getString("name") ?: "Unknown"
                    db.collection("users").document(userDoc.id)
                        .collection("feedback").get()
                        .addOnSuccessListener { fbSnap ->
                            fbSnap.documents.forEach { fb ->
                                allFeedback.add(mapOf(
                                    "userName"    to userName,
                                    "productName" to (fb.getString("productName") ?: "—"),
                                    "brand"       to (fb.getString("brand")       ?: "—"),
                                    "buyDecision" to (fb.getString("buyDecision") ?: "—"),
                                    "reason"      to (fb.getString("reason")      ?: "—"),
                                    "timestamp"   to (fb.getLong("timestamp")     ?: 0L)
                                ))
                            }
                            loadedCount++
                            if (loadedCount == userDocs.size) {
                                progressFeedback.visibility = View.GONE

                                // Sort newest first
                                val sorted = allFeedback.sortedByDescending {
                                    it["timestamp"] as Long
                                }

                                tvFeedbackCount.text = "${sorted.size} Feedback entries"
                                feedbackTableBody.removeAllViews()

                                sorted.forEachIndexed { index, fb ->
                                    val ts = fb["timestamp"] as Long
                                    val date = if (ts > 0) {
                                        java.text.SimpleDateFormat(
                                            "dd MMM, HH:mm",
                                            java.util.Locale.getDefault()
                                        ).format(java.util.Date(ts))
                                    } else "—"

                                    val row = buildFeedbackRow(
                                        index       = index + 1,
                                        userName    = fb["userName"]    as String,
                                        product     = fb["productName"] as String,
                                        brand       = fb["brand"]       as String,
                                        decision    = fb["buyDecision"] as String,
                                        reason      = fb["reason"]      as String,
                                        date        = date,
                                        isAlternate = index % 2 == 1
                                    )
                                    feedbackTableBody.addView(row)
                                }
                            }
                        }
                        .addOnFailureListener {
                            loadedCount++
                            if (loadedCount == userDocs.size) {
                                progressFeedback.visibility = View.GONE
                            }
                        }
                }
            }
            .addOnFailureListener {
                progressFeedback.visibility = View.GONE
                Toast.makeText(this, "Failed to load feedback: ${it.message}", Toast.LENGTH_SHORT).show()
            }

        // Default: show users tab
        selectTab(true)
    }

    // ── Build a user table row ─────────────────────────────────────
    private fun buildTableRow(
        index: Int,
        name: String,
        contact: String,
        rowIndex: Int
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                if (rowIndex % 2 == 1) android.graphics.Color.parseColor("#FFF8F5")
                else android.graphics.Color.WHITE
            )
            setPadding(
                (12 * dp).toInt(), (12 * dp).toInt(),
                (12 * dp).toInt(), (12 * dp).toInt()
            )
        }

        // Index number
        row.addView(TextView(this).apply {
            text = "$index"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                (28 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // Name
        row.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f
            )
        })

        // Contact / email
        row.addView(TextView(this).apply {
            text = contact
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f
            )
        })

        // Feedback count (filled in async after)
        row.addView(TextView(this).apply {
            tag = "col3_${rowIndex}"
            text = "loading..."
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })

        return row
    }

    // ── Build a feedback table row ─────────────────────────────────
    private fun buildFeedbackRow(
        index: Int,
        userName: String,
        product: String,
        brand: String,
        decision: String,
        reason: String,
        date: String,
        isAlternate: Boolean
    ): LinearLayout {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(
                if (isAlternate) android.graphics.Color.parseColor("#FFF8F5")
                else android.graphics.Color.WHITE
            )
            setPadding(
                (12 * dp).toInt(), (14 * dp).toInt(),
                (12 * dp).toInt(), (14 * dp).toInt()
            )
        }

        // Index
        row.addView(TextView(this).apply {
            text = "$index"
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                (24 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        // User name
        row.addView(TextView(this).apply {
            text = userName
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#111111"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f
            )
        })

        // FIX 1: product weight 2.5f — full name now visible
        row.addView(TextView(this).apply {
            text = product
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#444444"))
            maxLines = 2
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.5f
            ).apply { marginStart = (4 * dp).toInt() }
        })

        // Brand
        row.addView(TextView(this).apply {
            text = brand
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (4 * dp).toInt() }
        })

        // Buy decision badge
        row.addView(TextView(this).apply {
            text = decision
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20f
                setColor(
                    if (decision == "Yes") android.graphics.Color.parseColor("#16a34a")
                    else android.graphics.Color.parseColor("#DC2626")
                )
            }
            setPadding(
                (6 * dp).toInt(), (2 * dp).toInt(),
                (6 * dp).toInt(), (2 * dp).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                (38 * dp).toInt(), (22 * dp).toInt()
            ).apply { marginStart = (4 * dp).toInt() }
        })

        // Reason
        row.addView(TextView(this).apply {
            text = reason
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#E8622A"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f
            ).apply { marginStart = (4 * dp).toInt() }
        })

        // Date
        row.addView(TextView(this).apply {
            text = date
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f
            ).apply { marginStart = (4 * dp).toInt() }
        })

        return row
    }
}