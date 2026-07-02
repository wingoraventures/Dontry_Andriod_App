package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class OtpActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    private var verificationId = ""
    private var isEmail = false
    private var isRegister = false
    private var pendingName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp)
        applyWindowInsets(findViewById(android.R.id.content))

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        verificationId = intent.getStringExtra("verificationId") ?: ""
        val phone      = intent.getStringExtra("phone") ?: ""
        isEmail        = intent.getBooleanExtra("isEmail", false)
        isRegister     = intent.getBooleanExtra("isRegister", false)
        pendingName    = intent.getStringExtra("name") ?: ""

        val tvTitle   = findViewById<TextView>(R.id.tvTitle)
        val tvPhone   = findViewById<TextView>(R.id.tvPhoneHint)
        val etOtp     = findViewById<EditText>(R.id.etOtp)
        val btnVerify = findViewById<Button>(R.id.btnVerify)
        val tvResend  = findViewById<TextView>(R.id.tvResend)
        val tvBack    = findViewById<TextView>(R.id.tvBack)

        // Update title dynamically based on login type
        tvTitle.text = if (isEmail) "Verify your email" else "Verify your number"

        tvPhone.text = "Code sent to $phone"

        startResendTimer(tvResend)

        btnVerify.setOnClickListener {
            val code = etOtp.text.toString().trim()
            if (code.length < 6) {
                etOtp.error = "Enter 6-digit code"
                return@setOnClickListener
            }
            if (isEmail) verifyEmailOtp(code, phone)
            else verifyPhoneOtp(code, phone)
        }

        tvResend.setOnClickListener { finish() }
        tvBack.setOnClickListener { finish() }
    }

    // Email OTP — just compare the 6-digit string
    private fun verifyEmailOtp(code: String, email: String) {
        if (code == verificationId) {
            // OTP matched — now create Firebase account
            val password = intent.getStringExtra("password") ?: ""
            FirebaseAuth.getInstance()
                .createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid ?: return@addOnSuccessListener
                    saveAndProceed(uid, pendingName, email)
                }
                .addOnFailureListener {
                    Toast.makeText(this, "❌ ${it.message}", Toast.LENGTH_LONG).show()
                }
        } else {
            Toast.makeText(this, "❌ Wrong code. Try again.", Toast.LENGTH_LONG).show()
        }
    }

    // Phone OTP — Firebase credential
    private fun verifyPhoneOtp(code: String, phone: String) {
        val credential: PhoneAuthCredential =
            PhoneAuthProvider.getCredential(verificationId, code)

        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val uid = result.user?.uid ?: return@addOnSuccessListener
                saveAndProceed(uid, pendingName, phone)
            }
            .addOnFailureListener {
                Toast.makeText(this, "❌ Wrong code. Try again.", Toast.LENGTH_LONG).show()
            }
    }

    private fun saveAndProceed(uid: String, name: String, contact: String) {
        if (isRegister) {
            db.collection("users").document(uid).set(
                hashMapOf(
                    "name"      to name,
                    "contact"   to contact,
                    "createdAt" to System.currentTimeMillis()
                )
            )
        }
        Toast.makeText(this, "✅ Account verified!", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, OnboardingActivity::class.java))
        finishAffinity()
    }

    private fun startResendTimer(tvResend: TextView) {
        tvResend.isEnabled = false
        tvResend.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))

        object : CountDownTimer(60000, 1000) {
            override fun onTick(ms: Long) {
                tvResend.text = "Resend code in ${ms / 1000}s"
            }
            override fun onFinish() {
                tvResend.text = "Resend code"
                tvResend.isEnabled = true
                tvResend.setTextColor(android.graphics.Color.parseColor("#2E9E8A"))
            }
        }.start()
    }
}