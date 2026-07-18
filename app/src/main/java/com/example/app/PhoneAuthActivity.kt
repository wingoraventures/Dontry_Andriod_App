package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.appcheck.FirebaseAppCheck
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class PhoneAuthActivity : BaseActivity() {

    private lateinit var etPhone: EditText
    private lateinit var btnSend: Button
    private lateinit var tvBack: TextView
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_auth)
        applyWindowInsets(findViewById(android.R.id.content))

        etPhone = findViewById(R.id.etPhone)
        btnSend = findViewById(R.id.btnSendOtp)
        tvBack  = findViewById(R.id.tvBack)

        btnSend.setOnClickListener {
            val phone = etPhone.text.toString().trim()

            if (phone.isEmpty()) {
                etPhone.error = "Enter your phone number"
                return@setOnClickListener
            }

            val fullPhone = if (phone.startsWith("+")) phone else "+91$phone"
            sendOtp(fullPhone)
        }

        tvBack.setOnClickListener { finish() }
    }

    private fun sendOtp(phoneNumber: String) {
        btnSend.isEnabled = false
        btnSend.text = "Sending..."


        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                val appCheckToken = tokenResult.token

                val json = JSONObject().put("phone", phoneNumber).put("isRegister", false)
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${Constants.API_BASE_URL}/send-phone-otp")
                    .header("X-Firebase-AppCheck", appCheckToken)
                    .post(body)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            btnSend.isEnabled = true
                            btnSend.text = "Send OTP"
                            Toast.makeText(this@PhoneAuthActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val resBody = response.body?.string() ?: "{}"
                        val resJson = JSONObject(resBody)

                        runOnUiThread {
                            btnSend.isEnabled = true
                            btnSend.text = "Send OTP"

                            if (resJson.optBoolean("success")) {
                                val sessionId = resJson.optString("sessionId")

                                val intent = Intent(this@PhoneAuthActivity, OtpActivity::class.java)
                                intent.putExtra("verificationId", sessionId)
                                intent.putExtra("phone", phoneNumber)
                                intent.putExtra("isEmail", false)
                                intent.putExtra("isRegister", false)
                                intent.putExtra("password", "")
                                startActivity(intent)
                            } else {
                                val msg = resJson.optString("message", "Failed to send OTP")
                                Toast.makeText(this@PhoneAuthActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                })
            }
            .addOnFailureListener {
                runOnUiThread {
                    btnSend.isEnabled = true
                    btnSend.text = "Send OTP"
                    Toast.makeText(this@PhoneAuthActivity, "Security check failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
    }
}