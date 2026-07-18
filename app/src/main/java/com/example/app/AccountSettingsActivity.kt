package com.dontry.app

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AccountSettingsActivity : BaseActivity() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private val planDisplayNames = mapOf(
        "test"           to "Test Plan",
        "Free Credits"   to "Free Credits",
        "1tryon"    to "Starter",
        "5tryon"    to "Basic",
        "10tryon"   to "Popular",
        "50tryon"   to "Value",
        "100tryon"  to "Pro",
        "1000tryon" to "Ultimate"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_settings)
        applyWindowInsets(findViewById(android.R.id.content))

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        loadLocalProfilePhoto()
        loadAccountInfo()

        findViewById<TextView>(R.id.tvManagePlan).setOnClickListener {
            startActivity(android.content.Intent(this, SubscriptionActivity::class.java))
        }
    }

    private fun loadLocalProfilePhoto() {
        val prefs = getSharedPreferences("Dontry", MODE_PRIVATE)
        val photoPath = prefs.getString("profile_photo_path", null)
        val iv = findViewById<ImageView>(R.id.ivAvatar)

        val localFile = photoPath?.let { File(it) }
        if (localFile != null && localFile.exists()) {
            iv.setImageBitmap(BitmapFactory.decodeFile(photoPath))
        }
    }

    private fun loadAccountInfo() {
        val user = FirebaseAuth.getInstance().currentUser
        val uid = user?.uid
        if (uid == null) {
            Toast.makeText(this, "You're not signed in.", Toast.LENGTH_SHORT).show()
            return
        }

        // Sign-in method, shown immediately without waiting on Firestore
        findViewById<TextView>(R.id.tvSignInValue).text = resolveSignInMethod(user)

        // Fallback contact/email straight from FirebaseAuth in case Firestore is slow/offline
        val fallbackContact = user.email ?: user.phoneNumber ?: "—"
        findViewById<TextView>(R.id.tvContactValue).text = fallbackContact

        FirebaseFirestore.getInstance().collection("users").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) return@addOnSuccessListener

                val name = doc.getString("name") ?: ""
                if (name.isNotBlank()) {
                    findViewById<TextView>(R.id.tvName).text = name
                }

                val contact = doc.getString("contact")
                if (!contact.isNullOrBlank()) {
                    findViewById<TextView>(R.id.tvContactValue).text = contact
                }

                val createdAt = doc.getLong("createdAt")
                findViewById<TextView>(R.id.tvMemberSinceValue).text =
                    if (createdAt != null) dateFormat.format(Date(createdAt)) else "—"

                val planId = doc.getString("tryonPlan") ?: "Free Credits"
                findViewById<TextView>(R.id.tvPlanValue).text = planDisplayNames[planId] ?: planId

                val credits = doc.getLong("tryonsRemaining") ?: 0L
                findViewById<TextView>(R.id.tvCreditsValue).text = credits.toString()

                val validUntil = resolveDateField(doc, "activePlanExpiry")
                    ?: resolveDateField(doc, "planValidUntil")
                findViewById<TextView>(R.id.tvValidUntilValue).text = validUntil ?: "—"
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't load account details", Toast.LENGTH_SHORT).show()
            }
    }

    /** Handles the field being stored as a Long (millis), Firestore Timestamp, or null. */
    private fun resolveDateField(doc: com.google.firebase.firestore.DocumentSnapshot, field: String): String? {
        val raw = doc.get(field) ?: return null
        return when (raw) {
            is Long -> dateFormat.format(Date(raw))
            is com.google.firebase.Timestamp -> dateFormat.format(raw.toDate())
            else -> null
        }
    }

    private fun resolveSignInMethod(user: com.google.firebase.auth.FirebaseUser): String {
        val providerIds = user.providerData.map { it.providerId }
        return when {
            providerIds.contains(GoogleAuthProvider.PROVIDER_ID) -> "Google"
            providerIds.contains("phone") -> "Phone number"
            providerIds.contains("password") -> "Email & Password"
            else -> "Unknown"
        }
    }
}