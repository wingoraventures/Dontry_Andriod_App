package com.dontry.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class HelpSupportActivity : BaseActivity() {

    private val supportEmail = "Admin@dontry.in"

    private val supportWhatsAppNumber = "919544886842"

    private val faqs = listOf(
        "How does virtual try-on work?" to
                "Dontry uses your saved profile photo and overlays the garment from a product page " +
                "so you can preview how it may look on you before buying.",
        "Why do I need to enable Accessibility?" to
                "Accessibility access lets Dontry detect when you're on a supported shopping app's " +
                "product page so the floating try-on icon can appear.",
        "How do I change my profile photo?" to
                "Go to the home screen, tap \"Your Profile\", then pick a new photo from your gallery.",
        "What happens when my try-on credits run out?" to
                "You can top up or upgrade your plan from the Subscription screen in the side menu.",
        "Is my photo stored securely?" to
                "Your profile photo is stored on your device and synced securely to your account so " +
                "you can access it across sessions.",
        "How do I stop Dontry from running in the background?" to
                "Tap \"Stop Try-On\" on the home screen, or disable Dontry in your phone's " +
                "Accessibility settings."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help_support)
        applyWindowInsets(findViewById(android.R.id.content))

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvSupportEmail).text = supportEmail

        findViewById<LinearLayout>(R.id.rowContactSupport).setOnClickListener {
            sendSupportEmail()
        }

        findViewById<LinearLayout>(R.id.rowChatSupport).setOnClickListener {
            openWhatsAppChat()
        }

        buildFaqList()
    }

    private fun buildFaqList() {
        val container = findViewById<LinearLayout>(R.id.faqContainer)
        container.removeAllViews()

        for ((question, answer) in faqs) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.bottomMargin = dp(10)
                layoutParams = params
            }

            val questionView = TextView(this).apply {
                text = question
                setTextColor(0xFF1A1A1A.toInt())
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            val answerView = TextView(this).apply {
                text = answer
                setTextColor(0xFF9CA3AF.toInt())
                textSize = 12f
                visibility = android.view.View.GONE
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.topMargin = dp(6)
                layoutParams = params
            }

            itemLayout.setOnClickListener {
                answerView.visibility =
                    if (answerView.visibility == android.view.View.VISIBLE)
                        android.view.View.GONE
                    else
                        android.view.View.VISIBLE
            }

            itemLayout.addView(questionView)
            itemLayout.addView(answerView)
            container.addView(itemLayout)
        }
    }

    private fun sendSupportEmail() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(supportEmail))
            putExtra(Intent.EXTRA_SUBJECT, "Dontry Support Request")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No email app found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWhatsAppChat() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://wa.me/$supportWhatsAppNumber")
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open chat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}