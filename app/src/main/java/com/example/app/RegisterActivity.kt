package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.*
import androidx.core.content.ContextCompat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.firebase.FirebaseException
import com.google.firebase.auth.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import java.util.concurrent.TimeUnit
import java.util.Properties
import javax.mail.*
import javax.mail.internet.*
import kotlinx.coroutines.*

class RegisterActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient

    private val RC_GOOGLE = 1001


    private var pendingName = ""
    private var pendingContact = ""  // email or phone
    private var pendingPassword = ""
    private var generatedOtp = ""    // for email SMTP OTP

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

        // Style "Sign in" part in teal
        val fullText = "Already have an account? Sign in"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.teal)),
            fullText.indexOf("Sign in"),
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvLogin.text = spannable

        // Create Account button
        btnRegister.setOnClickListener {
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

            // Show loading state
            btnRegister.isEnabled = false
            btnRegister.text = "Please wait…"

            pendingName     = name
            pendingContact  = input
            pendingPassword = password

            when {
                Patterns.EMAIL_ADDRESS.matcher(input).matches() ->
                    sendEmailOtp(input, name, password)

                input.replace(" ", "").matches(Regex("^[+]?[0-9]{7,15}$")) ->
                    sendPhoneOtp(if (input.startsWith("+")) input else "+91$input")

                else -> {
                    etEmailOrPhone.error = "Enter a valid email or phone number"
                    resetRegisterButton()
                }
            }
        }

        // Google Sign-In
        btnGoogle.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                startActivityForResult(googleSignInClient.signInIntent, RC_GOOGLE)
            }
        }

        tvLogin.setOnClickListener { finish() }
    }

    private fun resetRegisterButton() {
        val btn = findViewById<Button>(R.id.btnRegister)
        btn.isEnabled = true
        btn.text = "Create Account"
    }


    private fun sendEmailOtp(email: String, name: String, password: String) {
        generatedOtp = (100000..999999).random().toString()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val smtpEmail    = BuildConfig.SMTP_EMAIL
                val smtpPassword = BuildConfig.SMTP_PASSWORD

                val props = Properties().apply {
                    put("mail.smtp.host", "smtp.gmail.com")
                    put("mail.smtp.port", "587")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.starttls.enable", "true")
                }

                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication() =
                        PasswordAuthentication(smtpEmail, smtpPassword)
                })

                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(smtpEmail, "Dontry"))
                    setRecipients(Message.RecipientType.TO, InternetAddress.parse(email))
                    subject = "Your Dontry verification code"
                    setText(
                        "Hi $name,\n\n" +
                                "Your Dontry verification code is: $generatedOtp\n\n" +
                                "This code expires in 10 minutes.\n\n" +
                                "— Dontry Team"
                    )
                }

                Transport.send(message)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@RegisterActivity,
                        "OTP sent to $email",
                        Toast.LENGTH_SHORT
                    ).show()

                    // Go to OTP screen
                    val intent = Intent(this@RegisterActivity, OtpActivity::class.java)
                    intent.putExtra("verificationId", generatedOtp)
                    intent.putExtra("phone", email)
                    intent.putExtra("name", pendingName)
                    intent.putExtra("password", pendingPassword)
                    intent.putExtra("isEmail", true)
                    intent.putExtra("isRegister", true)
                    startActivity(intent)

                    // Reset button after navigating away
                    resetRegisterButton()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resetRegisterButton()
                    Toast.makeText(
                        this@RegisterActivity,
                        "❌ Failed to send OTP: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private fun sendPhoneOtp(phone: String) {
        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phone)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {

                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    // Auto-verified (rare on real devices) — sign in directly
                    auth.signInWithCredential(credential)
                        .addOnSuccessListener { result ->
                            val uid = result.user?.uid ?: return@addOnSuccessListener
                            saveUserToFirestore(uid, pendingName, phone)
                            goToMain()
                        }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    resetRegisterButton()
                    Toast.makeText(
                        this@RegisterActivity,
                        "❌ ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    val intent = Intent(this@RegisterActivity, OtpActivity::class.java)
                    intent.putExtra("verificationId", verificationId)
                    intent.putExtra("phone", phone)
                    intent.putExtra("name", pendingName)
                    intent.putExtra("isEmail", false)
                    intent.putExtra("isRegister", true)
                    startActivity(intent)

                    // Reset button after navigating away
                    resetRegisterButton()
                }
            })
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
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
                        saveUserToFirestore(uid, name, email)
                        goToMain()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "❌ Google sign-in failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }

            } catch (e: ApiException) {
                Toast.makeText(this,
                    "❌ Code: ${e.statusCode} - ${e.message}",
                    Toast.LENGTH_LONG).show()
                android.util.Log.e("GOOGLE_SIGN_IN", "Error code: ${e.statusCode}")
            }
        }
    }


    fun saveUserToFirestore(uid: String, name: String, contact: String) {
        db.collection("users").document(uid).set(
            hashMapOf(
                "name"      to name,
                "contact"   to contact,
                "createdAt" to System.currentTimeMillis()
            )
        )
    }

    private fun goToMain() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }
}