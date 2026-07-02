package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit

class PhoneAuthActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var etPhone: EditText
    private lateinit var btnSend: Button
    private lateinit var tvBack: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_auth)
        applyWindowInsets(findViewById(android.R.id.content))


        auth = FirebaseAuth.getInstance()

        etPhone = findViewById(R.id.etPhone)
        btnSend = findViewById(R.id.btnSendOtp)
        tvBack  = findViewById(R.id.tvBack)

        btnSend.setOnClickListener {
            val phone = etPhone.text.toString().trim()

            if (phone.isEmpty()) {
                etPhone.error = "Enter your phone number"
                return@setOnClickListener
            }

            // Phone must include country code e.g. +919876543210
            val fullPhone = if (phone.startsWith("+")) phone else "+91$phone"

            sendOtp(fullPhone)
        }

        tvBack.setOnClickListener { finish() }
    }

    private fun sendOtp(phoneNumber: String) {
        btnSend.isEnabled = false
        btnSend.text = "Sending..."

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Auto-verification (rare on real devices)
                    auth.signInWithCredential(credential)
                        .addOnSuccessListener {
                            startActivity(Intent(this@PhoneAuthActivity, OnboardingActivity::class.java))
                            finish()
                        }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    btnSend.isEnabled = true
                    btnSend.text = "Send OTP"
                    Toast.makeText(this@PhoneAuthActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    // Go to OTP screen
                    val intent = Intent(this@PhoneAuthActivity, OtpActivity::class.java)
                    intent.putExtra("verificationId", verificationId)
                    intent.putExtra("phone", phoneNumber)
                    startActivity(intent)
                    btnSend.isEnabled = true
                    btnSend.text = "Send OTP"
                }
            })
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }
}