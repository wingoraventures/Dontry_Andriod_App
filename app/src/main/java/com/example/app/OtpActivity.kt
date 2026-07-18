package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.google.firebase.appcheck.FirebaseAppCheck

class OtpActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private val client = OkHttpClient()

    private var verificationId = ""  // sessionId (phone) or unused (email)
    private var isEmail = false
    private var isRegister = false
    private var pendingName = ""
    private var pendingPassword = ""
    private lateinit var phone: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_otp)
        applyWindowInsets(findViewById(android.R.id.content))

        auth = FirebaseAuth.getInstance()

        verificationId  = intent.getStringExtra("verificationId") ?: ""
        phone           = intent.getStringExtra("phone") ?: ""
        isEmail         = intent.getBooleanExtra("isEmail", false)
        isRegister      = intent.getBooleanExtra("isRegister", false)
        pendingName     = intent.getStringExtra("name") ?: ""
        pendingPassword = intent.getStringExtra("password") ?: ""

        val tvTitle   = findViewById<TextView>(R.id.tvTitle)
        val tvPhone   = findViewById<TextView>(R.id.tvPhoneHint)
        val etOtp     = findViewById<EditText>(R.id.etOtp)
        val btnVerify = findViewById<Button>(R.id.btnVerify)
        val tvResend  = findViewById<TextView>(R.id.tvResend)
        val tvBack    = findViewById<TextView>(R.id.tvBack)

        tvTitle.text = if (isEmail) "Verify your email" else "Verify your number"
        tvPhone.text = "Code sent to $phone"

        startResendTimer(tvResend)

        btnVerify.setOnClickListener {
            val code = etOtp.text.toString().trim()
            if (code.length < 4) {
                etOtp.error = "Enter 4-digit code"
                return@setOnClickListener
            }
            btnVerify.isEnabled = false
            btnVerify.text = "Verifying..."

            if (isEmail) verifyEmailOtp(code, phone, btnVerify)
            else verifyPhoneOtp(code, phone, btnVerify)
        }


        tvResend.setOnClickListener {
            if (tvResend.isEnabled) resendOtp(tvResend)
        }
        tvBack.setOnClickListener { finish() }
    }


    private fun resendOtp(tvResend: TextView) {
        tvResend.isEnabled = false

        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                val appCheckToken = tokenResult.token

                val json = JSONObject().apply {
                    if (isEmail) put("email", phone) else put("phone", phone)
                    put("password", pendingPassword)
                    put("isRegister", isRegister)
                }
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val url = if (isEmail) "${Constants.API_BASE_URL}/send-email-otp"
                else "${Constants.API_BASE_URL}/send-phone-otp"

                val request = Request.Builder()
                    .url(url)
                    .header("X-Firebase-AppCheck", appCheckToken)
                    .post(body)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            Toast.makeText(this@OtpActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                            startResendTimer(tvResend)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val resJson = JSONObject(response.body?.string() ?: "{}")
                        runOnUiThread {
                            if (resJson.optBoolean("success")) {
                                if (!isEmail) verificationId = resJson.optString("sessionId")
                                Toast.makeText(this@OtpActivity, "OTP resent", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@OtpActivity, "❌ ${resJson.optString("message")}", Toast.LENGTH_LONG).show()
                            }
                            startResendTimer(tvResend)
                        }
                    }
                })
            }
            .addOnFailureListener {
                runOnUiThread {
                    Toast.makeText(this@OtpActivity, "Security check failed: ${it.message}", Toast.LENGTH_LONG).show()
                    startResendTimer(tvResend)
                }
            }
    }


    private fun verifyEmailOtp(code: String, email: String, btnVerify: Button) {
        val json = JSONObject().put("email", email).put("otp", code)
        callVerify("${Constants.API_BASE_URL}/verify-email-otp", json, email, btnVerify)
    }

    private fun verifyPhoneOtp(code: String, phoneNum: String, btnVerify: Button) {
        val json = JSONObject()
            .put("sessionId", verificationId)
            .put("otp", code)
            .put("phone", phoneNum)
        callVerify("${Constants.API_BASE_URL}/verify-phone-otp", json, phoneNum, btnVerify)
    }

    private fun callVerify(url: String, json: JSONObject, contact: String, btnVerify: Button) {
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                val appCheckToken = tokenResult.token

                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .header("X-Firebase-AppCheck", appCheckToken)
                    .post(body)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            btnVerify.isEnabled = true
                            btnVerify.text = "Verify"
                            Toast.makeText(this@OtpActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val resJson = JSONObject(response.body?.string() ?: "{}")

                        runOnUiThread {
                            btnVerify.isEnabled = true
                            btnVerify.text = "Verify"

                            if (resJson.optBoolean("success")) {
                                val token = resJson.getString("token")
                                auth.signInWithCustomToken(token)
                                    .addOnSuccessListener { result ->
                                        val uid = result.user?.uid ?: return@addOnSuccessListener
                                        saveAndProceed(uid, pendingName, contact)
                                    }
                                    .addOnFailureListener {
                                        Toast.makeText(this@OtpActivity, "❌ Sign-in failed: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                            } else {
                                val msg = resJson.optString("message", "Invalid OTP")
                                Toast.makeText(this@OtpActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                })
            }
            .addOnFailureListener {
                runOnUiThread {
                    btnVerify.isEnabled = true
                    btnVerify.text = "Verify"
                    Toast.makeText(this@OtpActivity, "Security check failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
    }


    private fun saveAndProceed(uid: String, name: String, contact: String) {
        AuthHelper.ensureUserDoc(uid, name, contact) { isNew ->
            Toast.makeText(this@OtpActivity, "✅ Account verified!", Toast.LENGTH_SHORT).show()
            if (isNew) startActivity(Intent(this@OtpActivity, OnboardingActivity::class.java))
            else startActivity(Intent(this@OtpActivity, MainActivity::class.java))
            finishAffinity()
        }
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