package com.dontry.app
import android.app.*
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import android.app.PendingIntent
class FloatingService : Service() {

    companion object {
        var instance: FloatingService? = null
        var onResultClosed: (() -> Unit)? = null
    }

    private lateinit var windowManager: WindowManager
    private var iconView: View? = null
    private var panelView: View? = null
    private var iconParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isCapturing = false
    private var keepIconUntil: Long = 0L
    private var isAmazonWasOpen = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var resultImageUrl: String? = null

    private var dot1: ImageView? = null
    private var dot2: ImageView? = null
    private var dot3: ImageView? = null
    private var dotsAnimJob: Job? = null

    // ── Plan/credits cache (kept live via Firestore snapshot listener) ──
    private var cachedTryonsRemaining: Int? = null
    private var cachedTryonsTotal: Int? = null
    private var cachedPlanId: String? = null
    private var planListener: com.google.firebase.firestore.ListenerRegistration? = null


    private var selectedLookId: String? = null

    private var currentProductBrand: String = ""
    private var currentProductName: String = ""

    private val planDisplay = mapOf(
        "test"           to Triple("Test Plan", "5 tryons", "No expiry"),
        "Free Credits"   to Triple("Free Credits", "5 tryons", "No expiry"),
        "1tryon"    to Triple("Starter", "1 tryon", "Valid 30 days"),
        "5tryon"    to Triple("Basic", "5 tryons", "Valid 30 days"),
        "10tryon"   to Triple("Popular", "10 tryons", "Valid 30 days"),
        "50tryon"   to Triple("Value", "50 tryons", "Valid 30 days"),
        "100tryon"  to Triple("Pro", "100 tryons", "Valid 60 days"),
        "1000tryon" to Triple("Ultimate", "1000 tryons", "Valid 1 year")
    )



    // WakeLock
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var isIconAttachedToWindow = false

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "TryVue::TryOnWakeLock"
        )
        wakeLock?.acquire(60_000L)
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { e.printStackTrace() }
        wakeLock = null
    }

    // ─────────────────────────────────────────────────────────────
    //  PLAN / CREDITS — live cache via Firestore snapshot listener
    // ─────────────────────────────────────────────────────────────

    private fun startPlanListener() {
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.w("Dontry", "startPlanListener: no signed-in user, skipping")
            return
        }

        planListener = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.e("Dontry", "Plan listener error: ${error.message}")
                    return@addSnapshotListener
                }
                if (doc == null || !doc.exists()) return@addSnapshotListener

                cachedTryonsRemaining = (doc.getLong("tryonsRemaining") ?: 0).toInt()
                cachedTryonsTotal = (doc.getLong("tryonsTotal") ?: cachedTryonsRemaining!!.toLong()).toInt()
                cachedPlanId = doc.getString("tryonPlan") ?: "Test Plan"
            }
    }

    // ─────────────────────────────────────────────────────────────
    //  OVERLAY TOAST (works on all ROMs including Realme/OPPO/Xiaomi)
    // ─────────────────────────────────────────────────────────────

    private var toastView: View? = null
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    private fun showOverlayToast(message: String, durationMs: Long = 3000L) {
        toastHandler.post {
            // Remove any existing toast
            toastRunnable?.let { toastHandler.removeCallbacks(it) }
            toastView?.let {
                try { windowManager.removeView(it) } catch (e: Exception) {}
            }
            toastView = null

            val dp = resources.displayMetrics.density

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (80 * dp).toInt()
            }

            val tv = TextView(this).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(
                    (20 * dp).toInt(), (12 * dp).toInt(),
                    (20 * dp).toInt(), (12 * dp).toInt()
                )
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#CC1A1A2E"))
                    cornerRadius = 24 * dp
                }
                elevation = 10f * dp
            }

            toastView = tv
            try {
                windowManager.addView(tv, params)
            } catch (e: Exception) {
                e.printStackTrace()
                return@post
            }

            toastRunnable = Runnable {
                try {
                    toastView?.let { windowManager.removeView(it) }
                } catch (e: Exception) {}
                toastView = null
            }.also {
                toastHandler.postDelayed(it, durationMs)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  LIFECYCLE
    // ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startPlanListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP  // ← this.flags
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "floating_channel")
            .setContentTitle("Dontry Running")
            .setContentText("Watching for shopping apps...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopDotsAnim()
        serviceScope.cancel()
        releaseWakeLock()
        hideAll()
        planListener?.remove()
        planListener = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────
    //  CALLED BY TryVueAccessibilityService
    // ─────────────────────────────────────────────────────────────

    fun onAppChanged(packageName: String, isAmazon: Boolean) {
        val now = System.currentTimeMillis()
        val isHomeLauncher =
            packageName in TryVueAccessibilityService.HOME_LAUNCHERS
                    || packageName.contains("launcher")
                    || (packageName.contains("home")
                    && !packageName.contains("amazon"))


        if (isHomeLauncher || !isAmazon) {
            isAmazonWasOpen = false
            keepIconUntil = 0L
            hidePanel()
            hideIconSafely()
            return
        }



        when {
            isCapturing -> {
                isAmazonWasOpen = true
                if (!isShowing) {
                    showFloatingIcon()
                    showDotsIcon()
                } else {
                    showIconSafely()
                    showDotsIcon()
                }
                showOverlayToast("⏳ Still processing... Stay on this page!", 3500L)
            }
            now < keepIconUntil && isAmazonWasOpen -> {
                if (!isShowing) showFloatingIcon()
                else showIconSafely()
            }
            else -> {
                isAmazonWasOpen = true
                if (!isShowing) showFloatingIcon()
                else showIconSafely()
                val resultFile = java.io.File(filesDir, "tryon_result.png")
                if (resultImageUrl != null && resultFile.exists()) {
                    updateIconToGreenTick()
                    showOverlayToast("✅ Your try-on is ready! Tap to view", 4000L)
                }
            }
        }
    }


    private fun updateIconToGreenTick() {
        stopDotsAnim()
        onResultClosed = {
            Handler(Looper.getMainLooper()).post {
                showNormalIcon()
            }
        }

        val container = iconView as? FrameLayout ?: return
        container.removeAllViews()
        container.clearAnimation()
        val dp = resources.displayMetrics.density

        container.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#16a34a"))
        }

        val tickView = object : View(this@FloatingService) {
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    strokeWidth = 5f * dp
                    style = android.graphics.Paint.Style.STROKE
                    strokeCap = android.graphics.Paint.Cap.ROUND
                    strokeJoin = android.graphics.Paint.Join.ROUND
                }
                val w = width.toFloat(); val h = height.toFloat()
                val path = android.graphics.Path().apply {
                    moveTo(w * 0.22f, h * 0.52f)
                    lineTo(w * 0.42f, h * 0.70f)
                    lineTo(w * 0.75f, h * 0.32f)
                }
                canvas.drawPath(path, paint)
            }
        }

        container.addView(
            tickView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.visibility = View.VISIBLE
    }

    // ─────────────────────────────────────────────────────────────
    //  FLOATING ICON
    // ─────────────────────────────────────────────────────────────

    private fun showFloatingIcon() {
        if (isShowing) return
        try {
            val size = (64 * resources.displayMetrics.density).toInt()
            val dp = resources.displayMetrics.density

            val params = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.END; x = 24; y = 120 }
            iconParams = params

            val gradientBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((2.5f * dp).toInt(), Color.parseColor("#E0E0E0"))
            }

            val container = FrameLayout(this).apply {
                background = gradientBg
                elevation = 10f * dp
                outlineProvider = ViewOutlineProvider.BACKGROUND
                clipToOutline = true
            }

            val pad = (10 * dp).toInt()
            val imageView = ImageView(this).apply {
                setImageResource(R.drawable.logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
            }
            container.addView(
                imageView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            var iX = 0; var iY = 0; var iTX = 0f; var iTY = 0f; var moved = false

            container.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        iX = params.x; iY = params.y
                        iTX = event.rawX; iTY = event.rawY
                        moved = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - iTX; val dy = event.rawY - iTY
                        if (kotlin.math.abs(dx) > 5 || kotlin.math.abs(dy) > 5) moved = true
                        params.x = iX + dx.toInt(); params.y = iY + dy.toInt()
                        try {
                            if (iconView != null) windowManager.updateViewLayout(iconView, params)
                        } catch (e: Exception) {}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            when {
                                resultImageUrl != null -> openResultScreen()
                                !isCapturing -> {
                                    container.visibility = View.GONE
                                    showPanel()
                                }
                                else -> { /* processing */ }
                            }
                        }; true
                    }
                    else -> false
                }
            }

            iconView = container
            try {
                windowManager.addView(iconView, params)
                isIconAttachedToWindow = true
                isShowing = true

                FirebaseCrashlytics.getInstance().log(
                    "floating_icon_shown model=${android.os.Build.MODEL} manufacturer=${android.os.Build.MANUFACTURER} sdk=${android.os.Build.VERSION.SDK_INT}"
                )

            } catch (e: Exception) {
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("model", android.os.Build.MODEL)
                    setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                    setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                    recordException(e)
                }
                e.printStackTrace()
            }

        } catch (e: Exception) { e.printStackTrace() }
    }

    // ─────────────────────────────────────────────────────────────
    //  SHOW PANEL
    // ─────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelView != null) return
        if (isCapturing) return

        val panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.activity_tryon_panel, null)

        val looksManager = LooksManager(this)

        // Get views
        val ivOriginalPhoto = view.findViewById<ImageView>(R.id.ivOriginalPhoto)
        val vOriginalSelected = view.findViewById<View>(R.id.vOriginalSelected)
        val tvOriginalTick = view.findViewById<TextView>(R.id.tvOriginalTick)
        val llSavedLooks = view.findViewById<LinearLayout>(R.id.llSavedLooks)
        val tvSelectedLabel = view.findViewById<TextView>(R.id.tvSelectedLabel)


        // ── Fetch current plan + remaining tryons ──
        var currentTryonsRemaining = 0
        val uid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        val btnGenerate = view.findViewById<Button>(R.id.btnGenerate)

        // Insert plan/credits row (modern card style) above tvSelectedLabel
        val planContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (10 * resources.displayMetrics.density).toInt()
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F0F9F8"))
                cornerRadius = 12 * resources.displayMetrics.density
            }
            setPadding(
                (18 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (18 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt()
            )
        }

        val tryonsHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvTryonsLabel = TextView(this).apply {
            text = "Try-ons remaining"
            setTextColor(Color.parseColor("#374151"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTryonsCount = TextView(this).apply {
            text = "0 / 0"
            setTextColor(Color.parseColor("#111827"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        tryonsHeaderRow.addView(tvTryonsLabel)
        tryonsHeaderRow.addView(tvTryonsCount)

        val progressBarBg = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (10 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#E5E7EB"))
                cornerRadius = 5 * resources.displayMetrics.density
            }
        }

        val progressBarFill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            background = android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(Color.parseColor("#2E9E8A"), Color.parseColor("#E8622A"))
                cornerRadius = 5 * resources.displayMetrics.density
            }
        }
        progressBarBg.addView(progressBarFill)

        val planTextView = TextView(this).apply {
            text = "👑 Loading plan..."
            setTextColor(Color.parseColor("#10B981"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        planContainer.addView(tryonsHeaderRow)
        planContainer.addView(progressBarBg)
        planContainer.addView(planTextView)



        val parentOfLabel = tvSelectedLabel.parent as? ViewGroup
        val labelIndex = parentOfLabel?.indexOfChild(tvSelectedLabel) ?: -1
        if (parentOfLabel != null && labelIndex >= 0) {
            parentOfLabel.addView(planContainer, labelIndex)
        }

        fun updateButtonForCredits() {
            if (currentTryonsRemaining <= 0) {
                btnGenerate.text = "Upgrade • 0 left"
            } else {
                btnGenerate.text = "Generate Try-On • $currentTryonsRemaining left"
            }
            btnGenerate.isEnabled = true
        }

        fun applyPlanData(remaining: Int, total: Int, planId: String) {
            currentTryonsRemaining = remaining
            val displayName = planDisplay[planId]?.first ?: planId

            tvTryonsCount.text = "$remaining / $total"
            planTextView.text = "👑 Plan: $displayName"

            val fillWeight = if (total > 0) {
                (remaining.toFloat() / total).coerceIn(0f, 1f)
            } else 0f
            progressBarBg.removeAllViews()
            progressBarBg.addView(progressBarFill.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = fillWeight
                }
            })
            progressBarBg.addView(View(this@FloatingService).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 1f - fillWeight
                }
            })

            updateButtonForCredits()
        }

        // Button must never be actionable with unverified data
        btnGenerate.isEnabled = false
        btnGenerate.text = "Loading..."

        val cachedRemaining = cachedTryonsRemaining
        if (cachedRemaining != null) {
            // Fast path: data already synced by the background snapshot listener
            applyPlanData(cachedRemaining, cachedTryonsTotal ?: cachedRemaining, cachedPlanId ?: "Test Plan")
        } else if (uid != null) {
            // Cold-start fallback: listener hasn't delivered data yet
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("users").document(uid).get()
                .addOnSuccessListener { doc ->
                    val remaining = (doc.getLong("tryonsRemaining") ?: 0).toInt()
                    val total = (doc.getLong("tryonsTotal") ?: remaining.toLong()).toInt()
                    val planId = doc.getString("tryonPlan") ?: "Test Plan"
                    applyPlanData(remaining, total, planId)
                }
                .addOnFailureListener { e ->
                    Log.e("Dontry", "Plan fetch failed: ${e.message}")
                    planTextView.text = "👑 Couldn't load plan"
                    btnGenerate.text = "Retry"
                    btnGenerate.isEnabled = true
                    btnGenerate.setOnClickListener {
                        hidePanel()
                        showIconSafely()
                        showPanel()
                    }
                    showOverlayToast("⚠️ Couldn't load your plan, tap Retry", 3000L)
                }
        }

        // Load original profile photo
        val prefs = getSharedPreferences("Dontry", MODE_PRIVATE)
        val originalPhotoPath = prefs.getString("profile_photo_path", null)

        originalPhotoPath?.let {
            if (java.io.File(it).exists()) {
                ivOriginalPhoto.setImageBitmap(BitmapFactory.decodeFile(it))
                ivOriginalPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
                ivOriginalPhoto.clipToOutline = true
                ivOriginalPhoto.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
        }
        ivOriginalPhoto.setOnClickListener {
            hidePanel()
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            })
        }

        // ── Function to update UI based on selection ──────────────
        fun updateSelectionUI(selectedId: String?) {
            selectedLookId = selectedId

            if (selectedId == null) {
                vOriginalSelected.visibility = View.VISIBLE
                tvOriginalTick.visibility = View.VISIBLE
                tvSelectedLabel.text = "Using: Original photo"
                tvSelectedLabel.setTextColor(Color.parseColor("#2E9E8A"))
                looksManager.clearSelectedLook()
            } else {
                vOriginalSelected.visibility = View.INVISIBLE
                tvOriginalTick.visibility = View.INVISIBLE

                val looks = looksManager.getAllLooks()
                val selectedLook = looks.find { it.id == selectedId }
                selectedLook?.let {
                    tvSelectedLabel.text = "Using: Saved look 👍"
                    tvSelectedLabel.setTextColor(Color.parseColor("#E8622A"))
                    looksManager.selectLook(it)
                }
            }
        }

        // ── Function to build looks row ───────────────────────────
        fun buildLooksRow() {
            llSavedLooks.removeAllViews()
            val looks = looksManager.getAllLooks()
            val dp = resources.displayMetrics.density

            looks.forEach { look ->
                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        (72 * dp).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (10 * dp).toInt() }
                }

                val frameLayout = FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (64 * dp).toInt(),
                        (64 * dp).toInt()
                    ).apply { bottomMargin = (4 * dp).toInt() }
                }

                val ivLook = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = resources.getDrawable(R.drawable.bg_avatar_circle, null)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(BitmapFactory.decodeFile(look.filePath))
                    clipToOutline = true
                }

                val vSelected = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    background = resources.getDrawable(R.drawable.bg_selected_border, null)
                    visibility = if (looksManager.isSelected(look)) View.VISIBLE else View.INVISIBLE
                }

                val tvTick = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (18 * dp).toInt(),
                        (18 * dp).toInt()
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                    }
                    text = "✓"
                    setTextColor(Color.WHITE)
                    textSize = 9f
                    gravity = Gravity.CENTER
                    background = resources.getDrawable(R.drawable.bg_tick_green, null)
                    visibility = if (looksManager.isSelected(look)) View.VISIBLE else View.INVISIBLE
                }

                val tvDelete = TextView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        (18 * dp).toInt(),
                        (18 * dp).toInt()
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                    }
                    text = "🗑"
                    textSize = 9f
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(Color.parseColor("#ff4444"))
                    }
                }

                tvDelete.setOnClickListener {
                    looksManager.deleteLook(look.id)
                    if (selectedLookId == look.id) {
                        updateSelectionUI(null)
                    }
                    buildLooksRow()
                    showOverlayToast("🗑 Look deleted", 2000L)
                }

                ivLook.setOnClickListener {
                    updateSelectionUI(look.id)
                    buildLooksRow()
                }

                frameLayout.addView(ivLook)
                frameLayout.addView(vSelected)
                frameLayout.addView(tvTick)
                frameLayout.addView(tvDelete)

                val tvLabel = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    val index = looks.indexOf(look) + 1
                    text = "Look $index"
                    setTextColor(Color.parseColor("#1a1a1a"))
                    textSize = 9f
                    gravity = Gravity.CENTER
                }

                itemLayout.addView(frameLayout)
                itemLayout.addView(tvLabel)
                llSavedLooks.addView(itemLayout)
            }
        }


        view.findViewById<LinearLayout>(R.id.llOriginalPhoto).setOnClickListener {
            updateSelectionUI(null)
            buildLooksRow()
        }


        val currentSelectedPath = looksManager.getSelectedLookPath()
        val looks = looksManager.getAllLooks()
        val currentSelectedLook = looks.find { it.filePath == currentSelectedPath }

        if (currentSelectedLook != null) {
            updateSelectionUI(currentSelectedLook.id)
        } else {
            updateSelectionUI(null)
        }

        buildLooksRow()

        // Close button
        view.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            hidePanel()
            showIconSafely()
        }


        // Generate button
        btnGenerate.setOnClickListener {
            if (isCapturing) return@setOnClickListener

            // If no credits, navigate to subscription
            if (currentTryonsRemaining <= 0) {
                hidePanel()
                val intent = Intent(this, SubscriptionActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                return@setOnClickListener
            }

            isCapturing = true
            (it as Button).isEnabled = false
            hidePanel()
            Handler(Looper.getMainLooper()).postDelayed({
                serviceScope.launch {

                    showDotsIcon()
                    acquireWakeLock()

                    val accService = TryVueAccessibilityService.instance
                    if (accService == null) {
                        showOverlayToast("⚠️ Please enable Dontry in Accessibility Settings first", 4000L)
                        isCapturing = false
                        showNormalIcon()
                        return@launch
                    }


                    val accTextNodes = accService.getProductTextNodes(100)

                    var screenshot: Bitmap? = null
                    val latch = java.util.concurrent.CountDownLatch(1)

                    accService.captureScreen { bmp ->
                        screenshot = bmp
                        latch.countDown()
                    }

                    withContext(Dispatchers.IO) {
                        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
                    }

                    if (screenshot == null) {
                        FirebaseCrashlytics.getInstance().apply {
                            setCustomKey("model", android.os.Build.MODEL)
                            setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                            setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                            recordException(Exception("Screenshot null after 8s latch"))
                        }
                        showOverlayToast("❌ Screenshot failed, try again", 4000L)
                        isCapturing = false
                        pinIcon(5000)
                        showNormalIcon()
                        return@launch
                    }

                    showOverlayToast("🔍 Checking for clothing...\n📌 Stay on this page, your try-on is underway!", 3500L)

                    val tryOnManager = TryOnManager(this@FloatingService)
                    val validation = withContext(Dispatchers.IO) {
                        tryOnManager.checkImageWithGemini(screenshot!!, accTextNodes)
                    }

                    when (val geminiResult = validation.geminiResult) {
                        is GeminiResult.NoClothing -> {
                            if (!screenshot!!.isRecycled) screenshot!!.recycle()
                            val message = if (validation.reason.isNotBlank()) {
                                "🚫 ${validation.reason}"
                            } else {
                                "👕 This doesn't look like a clothing product.\nOpen an Amazon clothing item page and tap Try On."
                            }
                            showOverlayToast(message, 5000L)
                            isCapturing = false
                            pinIcon(5000)
                            showNormalIcon()
                            return@launch
                        }

                        is GeminiResult.PartialGarment -> {
                            if (!screenshot!!.isRecycled) screenshot!!.recycle()
                            showOverlayToast(
                                "👗 Garment found but not fully visible.\nScroll to show the full clothing item and tap Try On.",
                                5000L
                            )
                            isCapturing = false
                            pinIcon(6000)
                            showNormalIcon()
                            return@launch
                        }

                        is GeminiResult.UnclearGarment -> {
                            if (!screenshot!!.isRecycled) screenshot!!.recycle()
                            showOverlayToast(
                                "📜 No garment visible here!\nScroll up to the main product photo at the top of the page and tap Try On again.",
                                5000L
                            )
                            isCapturing = false
                            pinIcon(6000)
                            showNormalIcon()
                            return@launch
                        }

                        is GeminiResult.Error -> {
                            FirebaseCrashlytics.getInstance().apply {
                                setCustomKey("model", android.os.Build.MODEL)
                                setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                                setCustomKey("gemini_error", geminiResult.message)
                                recordException(Exception("Gemini failed: ${geminiResult.message}"))
                            }
                            if (!screenshot!!.isRecycled) screenshot!!.recycle()
                            showOverlayToast("❌ Check failed: ${geminiResult.message}", 4000L)
                            isCapturing = false
                            pinIcon(5000)
                            showNormalIcon()
                            return@launch
                        }

                        is GeminiResult.HasClothing -> {

                            currentProductBrand = validation.productBrand
                            currentProductName  = validation.productTitle

                            showOverlayToast("✅ Clothing found!\n📌 Stay on this page while processing...", 5000L)

                            try {
                                val result = withContext(Dispatchers.IO) {
                                    tryOnManager.startTryOn(
                                        screenshot!!,
                                        validation.sessionId,
                                        validation.garmentClass,
                                        validation.validateTimeMs
                                    ) { progress ->
                                        Log.d("Dontry", "TryOn progress: $progress")
                                    }
                                }

                                when (result) {
                                    is TryOnResult.Success -> showGreenIcon(result.imageUrl)
                                    is TryOnResult.Error -> {
                                        FirebaseCrashlytics.getInstance().apply {
                                            setCustomKey("model", android.os.Build.MODEL)
                                            setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                                            setCustomKey("tryon_error", result.message)
                                            recordException(Exception("TryOn API error: ${result.message}"))
                                        }
                                        pinIcon(8000)
                                        showOverlayToast("❌ ${result.message}", 4000L)
                                        showNormalIcon()
                                    }
                                }
                            } finally {
                                if (!screenshot!!.isRecycled) screenshot!!.recycle()
                                isCapturing = false
                                releaseWakeLock()
                            }
                        }
                    }
                }
            }, 800L)
        }

        panelView = view
        windowManager.addView(panelView, panelParams)
    }



    private fun showNormalIcon() {
        resultImageUrl = null
        stopDotsAnim()

        val dp = resources.displayMetrics.density
        val container = iconView as? FrameLayout ?: return
        container.removeAllViews()
        container.clearAnimation()

        val gradientBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke((2.5f * dp).toInt(), Color.parseColor("#E0E0E0"))
        }
        container.background = gradientBg
        container.elevation = 10f * dp
        container.outlineProvider = ViewOutlineProvider.BACKGROUND
        container.clipToOutline = true

        val pad = (10 * dp).toInt()
        val imageView = ImageView(this).apply {
            setImageResource(R.drawable.logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(pad, pad, pad, pad)
        }
        container.addView(
            imageView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.visibility = View.VISIBLE
    }

    private fun showDotsIcon() {
        stopDotsAnim()

        val container = iconView as? FrameLayout ?: return
        container.removeAllViews()
        container.clearAnimation()

        val dp = resources.displayMetrics.density

        val pillBg = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#CC1A1A2E"))
        }
        container.background = pillBg
        container.outlineProvider = ViewOutlineProvider.BACKGROUND
        container.clipToOutline = true

        val dotSize = (12 * dp).toInt()
        val spacing = (5 * dp).toInt()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun makeDot(colorHex: String): ImageView {
            return ImageView(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor(colorHex))
                }
                elevation = 4f * dp
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = spacing
                }
            }
        }

        val d1 = makeDot("#00CFFF")
        val d2 = makeDot("#FF3366")
        val d3 = makeDot("#CC44FF")
        d3.layoutParams = (d3.layoutParams as LinearLayout.LayoutParams).apply {
            marginEnd = 0
        }

        row.addView(d1); row.addView(d2); row.addView(d3)

        val rowParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }

        container.addView(row, rowParams)
        container.visibility = View.VISIBLE

        dot1 = d1; dot2 = d2; dot3 = d3

        dotsAnimJob = serviceScope.launch {
            val dots = listOf(d1, d2, d3)
            val delays = listOf(0L, 180L, 360L)
            dots.forEachIndexed { i, dot ->
                val anim = android.view.animation.ScaleAnimation(
                    0.5f, 1.4f, 0.5f, 1.4f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 480
                    repeatMode = android.view.animation.Animation.REVERSE
                    repeatCount = android.view.animation.Animation.INFINITE
                    startOffset = delays[i]
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                }
                dot.startAnimation(anim)
            }
        }
    }

    private fun stopDotsAnim() {
        dotsAnimJob?.cancel()
        dotsAnimJob = null
        dot1?.clearAnimation(); dot2?.clearAnimation(); dot3?.clearAnimation()
        dot1 = null; dot2 = null; dot3 = null
    }

    private fun showGreenIcon(imageUrl: String) {
        resultImageUrl = imageUrl
        stopDotsAnim()

        onResultClosed = {
            Handler(Looper.getMainLooper()).post {
                resultImageUrl = null
                showNormalIcon()
                val currentPkg = TryVueAccessibilityService.instance
                    ?.rootInActiveWindow?.packageName?.toString()
                val onShoppingApp = currentPkg != null &&
                        currentPkg in TryVueAccessibilityService.TARGET_APPS
                if (onShoppingApp) {
                    showIconSafely()
                    pinIcon(8000)
                } else {
                    hideIconSafely()
                }
            }
        }

        serviceScope.launch {
            try {
                val bytes = when {
                    imageUrl.startsWith("data:image") -> {
                        val base64 = imageUrl.substringAfter(",")
                        android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                    }
                    else -> android.util.Base64.decode(imageUrl, android.util.Base64.DEFAULT)
                }

                val file = java.io.File(filesDir, "tryon_result.png")
                file.writeBytes(bytes)

                val container = iconView as? FrameLayout ?: return@launch
                container.removeAllViews()

                val dp = resources.displayMetrics.density

                container.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#16a34a"))
                }

                val tickView = object : View(this@FloatingService) {
                    override fun onDraw(canvas: android.graphics.Canvas) {
                        super.onDraw(canvas)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.WHITE
                            strokeWidth = 5f * dp
                            style = android.graphics.Paint.Style.STROKE
                            strokeCap = android.graphics.Paint.Cap.ROUND
                            strokeJoin = android.graphics.Paint.Join.ROUND
                        }
                        val w = width.toFloat(); val h = height.toFloat()
                        val path = android.graphics.Path().apply {
                            moveTo(w * 0.22f, h * 0.52f)
                            lineTo(w * 0.42f, h * 0.70f)
                            lineTo(w * 0.75f, h * 0.32f)
                        }
                        canvas.drawPath(path, paint)
                    }
                }

                container.addView(
                    tickView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )

                val popIn = android.view.animation.ScaleAnimation(
                    0f, 1f, 0f, 1f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 350
                    interpolator = android.view.animation.OvershootInterpolator(2.0f)
                    fillAfter = true
                }
                container.startAnimation(popIn)
                container.visibility = View.VISIBLE
                pinIcon(30000)

                showOverlayToast("✅ Done! Tap icon to see result", 4000L)

            } catch (e: Exception) {
                Log.e("Dontry", "Failed to save result: ${e.message}")
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("model", android.os.Build.MODEL)
                    setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                    setCustomKey("files_dir_writable", filesDir.canWrite())
                    recordException(e)
                }
                showNormalIcon()
                showOverlayToast("❌ Failed to save result", 4000L)
            }
        }
    }

    private fun pinIcon(durationMs: Long) {
        keepIconUntil = System.currentTimeMillis() + durationMs
    }

    private fun hideIconSafely(reason: String = "normal") {
        if (iconView != null) {
            try {
                windowManager.removeView(iconView)
            } catch (e: Exception) {
                // ── OEM forcefully removed our view ──────────────
                FirebaseCrashlytics.getInstance().apply {
                    setCustomKey("model", android.os.Build.MODEL)
                    setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                    setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                    setCustomKey("reason", reason)
                    recordException(e)
                }
                e.printStackTrace()
            }
            iconView = null
            isIconAttachedToWindow = false
        }
        isShowing = false
    }

    private fun showIconSafely() {
        if (!isShowing) {
            showFloatingIcon()
        } else {
            // Icon window still exists but was hidden (GONE) when the panel opened
            (iconView as? FrameLayout)?.visibility = View.VISIBLE
        }
    }

    private fun openResultScreen() {
        val file = java.io.File(filesDir, "tryon_result.png")
        if (!file.exists()) {
            showOverlayToast("Result not ready", 2000L)
            return
        }

        hideIconSafely()

        startActivity(Intent(this, ResultActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_HISTORY
            putExtra("result_image_path", file.absolutePath)
            // product_name + product_brand will come from accessibility service later
            putExtra("product_name", currentProductName)
            putExtra("product_brand", currentProductBrand)
        })
    }

    fun isPanelOpen(): Boolean = panelView != null
    private fun hidePanel() {
        try { panelView?.let { windowManager.removeView(it) }; panelView = null }
        catch (e: Exception) { e.printStackTrace() }
    }

    private fun hideAll() {
        stopDotsAnim()
        hidePanel()

        toastRunnable?.let { toastHandler.removeCallbacks(it) }
        toastView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        toastView = null
        try {
            iconView?.let { windowManager.removeView(it) }
            iconView = null
            isShowing = false
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "floating_channel", "TryVue Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
