package com.dontry.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.util.Patterns
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import com.google.firebase.appcheck.FirebaseAppCheck

class RegisterActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private val client = OkHttpClient()

    private val RC_GOOGLE = 1001

    private var pendingName = ""
    private var pendingContact = ""
    private var pendingPassword = ""

    private lateinit var cbConsent: CheckBox

    companion object {
        private const val TERMS_URL = "https://jithin-ji.github.io/dontry-privacy/terms.html"
        private const val PRIVACY_URL = "https://jithin-ji.github.io/dontry-privacy/"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        applyWindowInsets(findViewById(android.R.id.content))

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val etName         = findViewById<EditText>(R.id.etName)
        val etEmailOrPhone = findViewById<EditText>(R.id.etEmailOrPhone)
        val etPassword     = findViewById<EditText>(R.id.etPassword)
        val btnRegister    = findViewById<Button>(R.id.btnRegister)
        val btnGoogle      = findViewById<LinearLayout>(R.id.btnGoogle)
        val tvLogin        = findViewById<TextView>(R.id.tvLogin)
        cbConsent          = findViewById(R.id.cbConsent)
        val tvConsent      = findViewById<TextView>(R.id.tvConsent)

        // ── "Already have an account? Sign in" styling ──
        val fullText = "Already have an account? Sign in"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.teal)),
            fullText.indexOf("Sign in"),
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvLogin.text = spannable

        // ── Consent text with clickable Terms of Service + Privacy Policy ──
        setupConsentText(tvConsent)

        btnRegister.setOnClickListener {
            if (!cbConsent.isChecked) {
                Toast.makeText(this, "Please agree to the Terms of Service and Privacy Policy", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val name     = etName.text.toString().trim()
            val input    = etEmailOrPhone.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (name.isEmpty()) {
                etName.error = "Enter your full name"
                return@setOnClickListener
            }
            if (input.isEmpty()) {
                etEmailOrPhone.error = "Enter email or phone number"
                return@setOnClickListener
            }
            if (password.length < 8) {
                etPassword.error = "Password must be at least 8 characters"
                return@setOnClickListener
            }

            btnRegister.isEnabled = false
            btnRegister.text = "Please wait…"

            pendingName     = name
            pendingContact  = input
            pendingPassword = password

            when {
                Patterns.EMAIL_ADDRESS.matcher(input).matches() ->
                    sendEmailOtp(input)

                input.replace(" ", "").matches(Regex("^[+]?[0-9]{7,15}$")) ->
                    sendPhoneOtp(if (input.startsWith("+")) input else "+91$input")

                else -> {
                    etEmailOrPhone.error = "Enter a valid email or phone number"
                    resetRegisterButton()
                }
            }
        }

        btnGoogle.setOnClickListener {
            if (!cbConsent.isChecked) {
                Toast.makeText(this, "Please agree to the Terms of Service and Privacy Policy", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            googleSignInClient.signOut().addOnCompleteListener {
                startActivityForResult(googleSignInClient.signInIntent, RC_GOOGLE)
            }
        }

        tvLogin.setOnClickListener { finish() }
    }

    private fun setupConsentText(tvConsent: TextView) {
        val fullText = "I agree to the Terms of Service and Privacy Policy"
        val spannable = SpannableString(fullText)

        val termsStart = fullText.indexOf("Terms of Service")
        val termsEnd   = termsStart + "Terms of Service".length
        val privacyStart = fullText.indexOf("Privacy Policy")
        val privacyEnd   = privacyStart + "Privacy Policy".length

        val tealColor = ContextCompat.getColor(this, R.color.teal)

        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: android.view.View) {
                openUrl(TERMS_URL)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = tealColor
                ds.isUnderlineText = false
            }
        }, termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: android.view.View) {
                openUrl(PRIVACY_URL)
            }
            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = tealColor
                ds.isUnderlineText = false
            }
        }, privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        tvConsent.text = spannable
        tvConsent.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetRegisterButton() {
        val btn = findViewById<Button>(R.id.btnRegister)
        btn.isEnabled = true
        btn.text = "Create Account"
    }

    private fun sendEmailOtp(email: String) {
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                val appCheckToken = tokenResult.token

                val json = JSONObject().put("email", email).put("password", pendingPassword).put("isRegister", true)
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${Constants.API_BASE_URL}/send-email-otp")
                    .header("X-Firebase-AppCheck", appCheckToken)
                    .post(body)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            resetRegisterButton()
                            Toast.makeText(this@RegisterActivity, "❌ Failed to send OTP: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val resJson = JSONObject(response.body?.string() ?: "{}")
                        runOnUiThread {
                            resetRegisterButton()
                            if (resJson.optBoolean("success")) {
                                Toast.makeText(this@RegisterActivity, "OTP sent to $email", Toast.LENGTH_SHORT).show()

                                val intent = Intent(this@RegisterActivity, OtpActivity::class.java)
                                intent.putExtra("verificationId", "")
                                intent.putExtra("phone", email)
                                intent.putExtra("name", pendingName)
                                intent.putExtra("password", pendingPassword)
                                intent.putExtra("isEmail", true)
                                intent.putExtra("isRegister", true)
                                startActivity(intent)
                            } else {
                                val msg = resJson.optString("message", "Failed to send OTP")
                                Toast.makeText(this@RegisterActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                })
            }
            .addOnFailureListener {
                runOnUiThread {
                    resetRegisterButton()
                    Toast.makeText(this@RegisterActivity, "Security check failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun sendPhoneOtp(phone: String) {
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
            .addOnSuccessListener { tokenResult ->
                val appCheckToken = tokenResult.token

                val json = JSONObject().put("phone", phone).put("password", pendingPassword).put("isRegister", true)
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${Constants.API_BASE_URL}/send-phone-otp")
                    .header("X-Firebase-AppCheck", appCheckToken)
                    .post(body)
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        runOnUiThread {
                            resetRegisterButton()
                            Toast.makeText(this@RegisterActivity, "❌ ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val resJson = JSONObject(response.body?.string() ?: "{}")
                        runOnUiThread {
                            resetRegisterButton()
                            if (resJson.optBoolean("success")) {
                                val sessionId = resJson.optString("sessionId")

                                val intent = Intent(this@RegisterActivity, OtpActivity::class.java)
                                intent.putExtra("verificationId", sessionId)
                                intent.putExtra("phone", phone)
                                intent.putExtra("name", pendingName)
                                intent.putExtra("password", pendingPassword)
                                intent.putExtra("isEmail", false)
                                intent.putExtra("isRegister", true)
                                startActivity(intent)
                            } else {
                                val msg = resJson.optString("message", "Failed to send OTP")
                                Toast.makeText(this@RegisterActivity, "❌ $msg", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                })
            }
            .addOnFailureListener {
                runOnUiThread {
                    resetRegisterButton()
                    Toast.makeText(this@RegisterActivity, "Security check failed: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_GOOGLE) {
            try {
                val account    = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)

                auth.signInWithCredential(credential)
                    .addOnSuccessListener { result ->
                        val uid   = result.user?.uid ?: return@addOnSuccessListener
                        val name  = result.user?.displayName ?: ""
                        val email = result.user?.email ?: ""
                        AuthHelper.ensureUserDoc(uid, name, email) { isNew ->
                            if (isNew) startActivity(Intent(this@RegisterActivity, OnboardingActivity::class.java))
                            else startActivity(Intent(this@RegisterActivity, MainActivity::class.java))
                            finish()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "❌ Google sign-in failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }

            } catch (e: ApiException) {
                Toast.makeText(this,
                    "❌ Code: ${e.statusCode} - ${e.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}