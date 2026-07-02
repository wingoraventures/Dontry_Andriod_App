package com.dontry.app

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore

class LoginActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var googleSignInClient: GoogleSignInClient
    private var emailEntered = false

    private val RC_GOOGLE = 1001

    // ── Admin secret tap vars ──────────────────────────────────────
    private var logoTapCount = 0
    private var lastTapTime  = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        applyWindowInsets(findViewById(android.R.id.content))

        auth = FirebaseAuth.getInstance()
        db   = FirebaseFirestore.getInstance()

        // Auto-login if already signed in
        if (auth.currentUser != null) {
            goToMain()
            return
        }

        // Google Sign-In setup
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        val etEmail    = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnSignIn  = findViewById<Button>(R.id.btnSignIn)
        val btnGoogle  = findViewById<LinearLayout>(R.id.btnGoogle)
        val btnPhone   = findViewById<LinearLayout>(R.id.btnPhone)
        val tvRegister = findViewById<TextView>(R.id.tvRegister)
        val ivLogo     = findViewById<ImageView>(R.id.ivAdminLogo)

        // ── Secret Admin: tap logo 7 times ─────────────────────────
        ivLogo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastTapTime > 2000) logoTapCount = 0
            lastTapTime = now
            logoTapCount++

            val remaining = 7 - logoTapCount
            when {
                logoTapCount in 3..6 -> {
                    Toast.makeText(
                        this,
                        "🔐 Tap $remaining more times for admin",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                logoTapCount >= 7 -> {
                    logoTapCount = 0
                    startActivity(Intent(this, AdminDashboardActivity::class.java))
                }
            }
        }

        // Style "Create Account" in teal
        val fullText = "Don't have an account? Create Account"
        val spannable = SpannableString(fullText)
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.teal)),
            fullText.indexOf("Create Account"),
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tvRegister.text = spannable

        // Continue / Sign In button
        btnSignIn.setOnClickListener {
            val email = etEmail.text.toString().trim()

            if (!emailEntered) {
                if (email.isEmpty()) { etEmail.error = "Please enter your email"; return@setOnClickListener }
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    etEmail.error = "Enter a valid email address"; return@setOnClickListener
                }
                etPassword.visibility = View.VISIBLE
                btnSignIn.text = "Sign In"
                emailEntered = true
                etPassword.requestFocus()
            } else {
                val password = etPassword.text.toString().trim()
                if (password.isEmpty()) { etPassword.error = "Please enter your password"; return@setOnClickListener }

                auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener {
                        Toast.makeText(this, "✅ Welcome back!", Toast.LENGTH_SHORT).show()
                        goToMain()
                    }
                    .addOnFailureListener { exception ->
                        val message = when (exception) {
                            is FirebaseAuthInvalidCredentialsException -> "Invalid email or password"
                            is FirebaseAuthInvalidUserException        -> "Account not found. Please sign up first"
                            else -> "Login failed: ${exception.message}"
                        }
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
            }
        }

        // Google Sign-In
        btnGoogle.setOnClickListener {
            googleSignInClient.signOut().addOnCompleteListener {
                startActivityForResult(googleSignInClient.signInIntent, RC_GOOGLE)
            }
        }

        btnPhone.setOnClickListener {
            startActivity(Intent(this, PhoneAuthActivity::class.java))
        }

        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
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

                        if (result.additionalUserInfo?.isNewUser == true) {
                            db.collection("users").document(uid).set(
                                hashMapOf(
                                    "name"      to name,
                                    "contact"   to email,
                                    "createdAt" to System.currentTimeMillis()
                                )
                            )
                        }
                        goToMain()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "❌ Google sign-in failed: ${it.message}", Toast.LENGTH_LONG).show()
                    }

            } catch (e: ApiException) {
                Toast.makeText(this, "❌ Google error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }
}