package com.dontry.app
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

class TryVueAccessibilityService : AccessibilityService() {

    companion object {
        var instance: TryVueAccessibilityService? = null
        var lastCapturedUrl: String? = null
        var lastCapturedAsin: String? = null

        val TARGET_APPS = setOf(
            "com.amazon.mShop.android.shopping",
            "in.amazon.mShop.android.shopping",
            "com.amazon.india.shopping",
            "com.flipkart.android",
            "com.meesho.supply",
            "com.myntra.android"
        )

        val HOME_LAUNCHERS = setOf(
            "com.miui.home",
            "com.mi.android.globallauncher",
            "com.android.launcher",
            "com.android.launcher2",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.oneplus.launcher",
            "com.samsung.android.app.launcher",
            "com.huawei.android.launcher",
            "com.gogo.launcher",
            "com.vivo.launcher",
            "com.iqoo.launcher",
            "com.oppo.launcher",
            "com.realme.launcher",
            "com.nothing.launcher",
            "com.motorola.launcher3",
            "com.lge.launcher3",
            "com.sec.android.app.launcher",
            "com.coloros.launcher",
            "com.transsion.launcher",
            "com.hihonor.android.launcher",
            "com.bbk.launcher2",
            "com.asus.launcher",
            "com.tecno.launcher",
            "com.infinix.launcher"
        )

        private val IGNORED_PACKAGES = setOf(
            "com.dontry.app",
            "com.android.systemui",
            "com.android.settings",
            "com.facemoji.lite.xiaomi",
            "com.google.android.inputmethod.latin",
            "com.miui.systemui",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
            "com.oppo.systemui",
            "com.samsung.android.systemui",
            "com.sec.android.systemui",
            "com.vivo.systemui",
            "android",
            "com.android.phone",
            "com.google.android.dialer"
        )



        private val NON_CLOTHING_HARD_REJECT_ATTRS = listOf(


            "heel type",
            "heel height",
            "sole material",
            "outsole material",
            "insole material",
            "midsole material",
            "toe style",
            "toe shape",
            "water resistance level",
            "water resistance",
            "outer material",           // Flipkart footwear label
            "type for casual",          // Flipkart footwear section label
            "type for sports",          // Flipkart footwear section label
            "shoe width",
            "ankle height",
            "arch support",
            "spike type",
            "cleat type",
            "boot shaft height",
            "boot shaft circumference",

            // ── Electronics / gadgets ─────────────────────────────
            "battery capacity",
            "battery life",
            "battery type",
            "charging time",
            "screen size",
            "display size",
            "display type",
            "display resolution",
            "processor",
            "ram",
            "rom",
            "storage capacity",
            "internal storage",
            "expandable storage",
            "camera resolution",
            "front camera",
            "rear camera",
            "operating system",
            "connectivity",
            "bluetooth version",
            "wi-fi",
            "wifi",
            "usb type",
            "hdmi",
            "refresh rate",
            "response time",
            "brightness",
            "contrast ratio",
            "viewing angle",
            "speaker output",
            "noise cancellation",
            "water proof rating",
            "ip rating",
            "ip67",
            "ip68",
            "sim type",
            "network type",
            "5g",
            "4g lte",
            "compatible devices",
            "compatible with",
            "wattage",
            "power consumption",
            "voltage",
            "ampere",

            // ── Furniture / home ──────────────────────────────────
            "weight capacity",
            "load capacity",
            "assembly required",
            "number of shelves",
            "number of drawers",
            "seat height",
            "table height",
            "mattress thickness",
            "frame material",
            "upholstery material",
            "foam density",
            "spring type",
            "filing capacity",
            "drawer type",

            // ── Kitchen / appliances ──────────────────────────────
            "capacity in litres",
            "capacity in liters",
            "bowl capacity",
            "jar capacity",
            "cooking capacity",
            "power source",
            "heating element",
            "speed settings",
            "rpm",
            "motor power",
            "blade material",
            "jar material",
            "number of burners",
            "flame type",
            "auto ignition",
            "defrost",
            "energy rating",
            "star rating",
            "compressor type",
            "refrigerant",

            // ── Sports equipment / bags ───────────────────────────
            "strap type",
            "strap length",
            "bag capacity",
            "bag volume",
            "number of compartments",
            "racket weight",
            "string tension",
            "grip size",
            "ball type",
            "wheel type",
            "frame size",
            "gear count",
            "brake type"
        )



        private val NON_CLOTHING_KEYWORDS = listOf(

            // ── Footwear ──────────────────────────────────────────
            "sneakers", "sneaker",
            "shoes", "shoe",
            "boots", "boot",
            "sandals", "sandal",
            "loafers", "loafer",
            "slippers", "slipper",
            "flip flops", "flip-flops",
            "heels", "stiletto",
            "wedges", "wedge heel",
            "moccasins", "moccasin",
            "oxfords", "oxford shoe",
            "brogues", "brogue",
            "derby shoes",
            "monk strap",
            "ballet flats", "ballerinas",
            "espadrilles",
            "clogs",
            "platform shoes",
            "chunky shoes",
            "walking shoes",
            "running shoes",
            "sports shoes",
            "casual shoes",
            "formal shoes",
            "outdoor shoes",
            "trekking shoes",
            "hiking boots",
            "ankle boots",
            "knee boots",
            "chelsea boots",
            "combat boots",
            "football boots",
            "cricket shoes",
            "basketball shoes",
            "tennis shoes",
            "cycling shoes",
            "water shoes",
            "lace-up shoes",
            "slip on shoes",
            "slip-on",
            "kolhapuri",
            "mojari",
            "juttis", "jutti",
            "चप्पल", "जूते", "जूता", "सैंडल", "बूट",

            // ── Bags / luggage ────────────────────────────────────
            "backpack", "backpacks",
            "laptop bag", "laptop bags",
            "handbag", "handbags",
            "tote bag", "tote bags",
            "messenger bag",
            "sling bag", "sling bags",
            "shoulder bag",
            "clutch", "clutches",
            "wallet", "wallets",
            "purse", "purses",
            "briefcase",
            "trolley bag",
            "luggage", "suitcase",
            "travel bag",
            "gym bag", "duffle bag",
            "school bag",
            "crossbody bag",
            "fanny pack", "waist bag",
            "बैग", "बटुआ",

            // ── Watches / jewellery ───────────────────────────────
            "watch", "watches",
            "smartwatch", "smart watch",
            "analog watch",
            "digital watch",
            "bracelet", "bracelets",
            "necklace", "necklaces",
            "earrings", "earring",
            "ring", "rings",
            "pendant",
            "chain",
            "anklet", "anklets",
            "bangle", "bangles",
            "mangalsutra",
            "nose pin",
            "brooch",
            "घड़ी", "गहने", "चूड़ी",

            // ── Electronics ───────────────────────────────────────
            "smartphone", "mobile phone", "cell phone",
            "tablet", "ipad",
            "laptop", "notebook computer",
            "desktop", "computer",
            "monitor", "display",
            "television", "tv", "smart tv",
            "headphones", "headphone",
            "earphones", "earphone",
            "earbuds", "earbud",
            "bluetooth speaker",
            "powerbank", "power bank",
            "charger", "adapter",
            "cable", "usb cable",
            "keyboard", "mouse",
            "webcam",
            "router", "modem",
            "hard disk", "ssd",
            "pen drive", "flash drive",
            "memory card",
            "camera", "dslr",
            "printer",
            "scanner",
            "projector",
            "gaming console",
            "smartband", "fitness band",
            "trimmer", "shaver",
            "hair dryer", "hair straightener",
            "electric toothbrush",

            // ── Home / furniture ──────────────────────────────────
            "sofa", "couch",
            "bed", "mattress",
            "pillow", "cushion",
            "blanket", "comforter",
            "curtain", "drape",
            "rug", "carpet",
            "table", "dining table",
            "chair", "office chair",
            "wardrobe", "almirah",
            "bookshelf", "bookcase",
            "cupboard",
            "lamp", "light fixture",
            "ceiling fan", "fan",
            "air conditioner", "ac",
            "heater",
            "vacuum cleaner",
            "iron box",

            // ── Kitchen ───────────────────────────────────────────
            "mixer grinder",
            "juicer",
            "blender",
            "air fryer",
            "microwave",
            "oven",
            "toaster",
            "coffee maker",
            "electric kettle",
            "induction cooktop",
            "pressure cooker",
            "cookware", "non-stick pan",
            "frying pan", "kadai",
            "utensils",
            "water bottle",
            "flask",
            "refrigerator", "fridge",
            "washing machine",
            "dishwasher",

            // ── Beauty / personal care ────────────────────────────
            "moisturizer", "moisturiser",
            "sunscreen", "sunblock",
            "serum",
            "foundation", "concealer",
            "lipstick", "lip gloss",
            "mascara", "eyeliner",
            "eyeshadow",
            "perfume", "deodorant",
            "shampoo", "conditioner",
            "face wash", "cleanser",
            "toner",
            "body lotion", "body wash",
            "hair oil", "hair serum",
            "nail polish",
            "razor", "epilator",

            // ── Books / stationery ────────────────────────────────
            "book", "novel", "textbook",
            "notebook", "diary",
            "pen", "pencil",
            "marker", "highlighter",

            // ── Toys / games ──────────────────────────────────────
            "toy", "toys",
            "board game",
            "puzzle",
            "action figure",
            "doll",
            "lego", "building blocks",
            "remote control car",

            // ── Sports equipment ──────────────────────────────────
            "cricket bat", "cricket ball",
            "football",
            "badminton racket", "tennis racket",
            "yoga mat",
            "dumbbell", "barbell",
            "treadmill",
            "cycle", "bicycle",
            "helmet",
            "knee pad", "elbow pad",
            "swimming goggles"
        )

        // ─────────────────────────────────────────────────────────
        //  CLOTHING DETECTION — ATTRIBUTE LISTS
        // ─────────────────────────────────────────────────────────

        private val STRONG_CLOTHING_ATTRS = listOf(
            "sleeve style",
            "sleeve type",
            "collar",
            "neck style",
            "shape type",
            "waist rise",
            "blouse piece",
            "fit type",
            "collar style",
            "sari style"
        )

        private val MEDIUM_CLOTHING_ATTRS = listOf(
            "fabric",
            "pattern",
            "occasion",
            "fit",
            "length",
            "distress",
            "fade",
            "material composition",
            "care instructions",
            "included components",
            "occasion type",
            "closure type"
        )

        // ─────────────────────────────────────────────────────────
        //  CLOTHING KEYWORDS
        // ─────────────────────────────────────────────────────────

        private val CLOTHING_KEYWORDS = listOf(
            // ── Tops / upper body ──────────────────────────────
            "shirt", "shirts",
            "t-shirt", "tshirt", "t shirt",
             "tops",
            "blouse", "blouses",
            "tunic", "tunics",
            "polo", "polo shirt",
            "sweatshirt", "sweatshirts",
            "hoodie", "hoodies",
            "jacket", "jackets",
            "blazer", "blazers",
            "coat", "overcoat",
            "cardigan", "cardigans",
            "sweater", "sweaters",
            "pullover", "pullovers",
            "vest", "vests",
            "tank top", "camisole",
            "crop top", "crop tops",

            // ── Bottoms ────────────────────────────────────────
            "jeans", "denim",
            "trousers", "trouser",
            "pants", "pant",
            "shorts", "short",
            "skirt", "skirts",
            "leggings", "legging",
            "joggers", "jogger",
            "track pants", "trackpants",
            "palazzos", "palazzo",
            "capri", "capris",

            // ── Full body / dresses ────────────────────────────
            "dress", "dresses",
            "gown", "gowns",
            "maxi dress", "midi dress", "mini dress",
            "jumpsuit", "jumpsuits",
            "playsuit",
            "romper", "rompers",
            "co-ord", "co ord", "coord set",

            // ── Indian ethnic wear ─────────────────────────────
            "saree", "sari", "साड़ी",
            "salwar", "salwar suit", "salwar kameez",
            "churidar", "churidars",
            "kurta", "kurtas", "kurti", "kurtis",
            "anarkali", "anarkali suit",
            "lehenga", "lehnga", "lehenga choli",
            "sharara", "gharara",
            "dupatta", "dupattas",
            "ethnic wear", "ethnic set",
            "indo western",
            "pathani", "pathani suit",
            "sherwani", "sherwanis",
            "dhoti", "dhotis",
            "pajama set", "pyjama set",
            "nightsuit", "night suit",
            "nighty", "nightgown",

            // ── Activewear / sportswear ────────────────────────
            "activewear",
            "sportswear",
            "gym wear", "gymwear",
            "yoga wear", "yoga pants",
            "cycling shorts",
            "compression tights",
            "sports bra",
            "athletic wear",

            // ── Accessories worn on body (clothing-adjacent) ───
            "stole", "stoles",
            "scarf", "scarves",
            "shawl", "shawls",
            "poncho",

            // ── Fabric / material signals ──────────────────────
            "cotton", "polyester", "silk", "linen",
            "chiffon", "georgette", "rayon", "velvet",
            "nylon", "spandex", "lycra", "modal",
            "wool", "cashmere", "fleece",
            "denim fabric", "pure cotton", "pure silk",

            // ── Fit / style descriptors (clothing-specific) ────
            "slim fit", "regular fit", "relaxed fit",
            "oversized", "fitted",
            "a-line", "bodycon", "flared",
            "printed", "embroidered", "solid colour",
            "stretchable",

            // ── Hindi clothing keywords ────────────────────────
            "कुर्ता", "कुर्ती", "शर्ट", "टी-शर्ट",
            "पैंट", "जींस", "लेगिंग",
            "ड्रेस", "गाउन",
            "सलवार", "कमीज़", "अनारकली",
            "लहंगा", "चोली",
            "पायजामा", "नाइटी",
            "एथनिक वियर", "वेस्टर्न वियर"
        )


        private val CART_BUTTON_SIGNALS = listOf(
            "add to cart",
            "add to basket",
            "कार्ट में जोड़ें",
            "add to bag",
            "बैग में जोड़ें"
        )

        private val BUY_NOW_SIGNALS = listOf(
            "buy now",
            "अभी खरीदें",
            "buy at ₹"
        )

        private const val MAX_RETRIES = 20
        private const val RETRY_INTERVAL_MS = 100L
    }

    private var lastPackage: String? = null
    private var lastIsProductPage: Boolean = false
    private var lastProductCheckTime = 0L

    private val HIDE_DELAY_MS = 2000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null
    private var pendingRetryRunnable: Runnable? = null
    private var hideAlreadyScheduled = false
    private var retryCount = 0

    private val bgExecutor = Executors.newSingleThreadExecutor()

    private val homeCheckHandler = Handler(Looper.getMainLooper())
    private var homeCheckRunnable: Runnable? = null

    private fun startPeriodicCheck() {
        stopPeriodicCheck()
        homeCheckRunnable = object : Runnable {
            override fun run() {
                try {
                    val floatingService = FloatingService.instance
                    if (floatingService != null && !floatingService.isPanelOpen()) {   // ← added guard
                        val pkg = try {
                            rootInActiveWindow?.packageName?.toString()
                                ?: windows?.firstOrNull()?.root?.packageName?.toString()
                        } catch (e: Exception) { null }

                        if (pkg != null && pkg !in IGNORED_PACKAGES) {
                            val isShoppingApp = pkg in TARGET_APPS
                            val isLauncher = pkg in HOME_LAUNCHERS
                                    || pkg.contains("launcher", ignoreCase = true)
                                    || pkg.contains(".home", ignoreCase = true)
                            if (!isShoppingApp || isLauncher) {
                                floatingService.onAppChanged(pkg, isAmazon = false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Dontry", "Periodic check error: ${e.message}")
                }
                homeCheckHandler.postDelayed(this, 800L)
            }
        }
        homeCheckHandler.post(homeCheckRunnable!!)
    }

    private fun stopPeriodicCheck() {
        homeCheckRunnable?.let { homeCheckHandler.removeCallbacks(it) }
        homeCheckRunnable = null
    }



    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("Dontry", "✅ AccessibilityService connected")

        serviceInfo = serviceInfo.apply {
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags =
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 500
        }

        startPeriodicCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopPeriodicCheck()
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdown()
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg in IGNORED_PACKAGES) return

        val floatingService = FloatingService.instance ?: return

        if (pkg in HOME_LAUNCHERS) {
            cancelAll()
            floatingService.onAppChanged(pkg, isAmazon = false)
            lastPackage = pkg
            lastIsProductPage = false
            return
        }

        val isAmazon = pkg in TARGET_APPS
        if (!isAmazon) {
            cancelAll()
            floatingService.onAppChanged(pkg, isAmazon = false)
            lastPackage = pkg
            lastIsProductPage = false
            return
        }

        checkScreen(pkg, floatingService)
    }


    private fun isClothingByKeywords(text: String): Boolean {
        return CLOTHING_KEYWORDS.any { text.contains(it) }
    }

    /** Returns true if attribute matching identifies this as clothing. */
    private fun isClothingByAttributes(text: String): Boolean {
        if (STRONG_CLOTHING_ATTRS.any { text.contains(it) }) return true
        val mediumMatches = MEDIUM_CLOTHING_ATTRS.count { text.contains(it) }
        return mediumMatches >= 2
    }

    /** Returns true if non-clothing attributes are present. */
    private fun hasNonClothingAttr(text: String): Boolean {
        return NON_CLOTHING_HARD_REJECT_ATTRS.any { text.contains(it) }
    }

    /** Returns true if non-clothing keywords are present. */
    private fun hasNonClothingKeyword(text: String): Boolean {
        return NON_CLOTHING_KEYWORDS.any { text.contains(it) }
    }


    private fun detectClothing(productText: String): Pair<Boolean, String> {


        if (isClothingByKeywords(productText)) {
            return Pair(true, "keyword_match")
        }


        if (isClothingByAttributes(productText)) {
            if (hasNonClothingAttr(productText)) {
                return Pair(false, "attr_fallback_rejected_by_non_clothing_attr")
            }
            if (hasNonClothingKeyword(productText)) {
                return Pair(false, "attr_fallback_rejected_by_non_clothing_keyword")
            }
            return Pair(true, "attribute_fallback")
        }


        return Pair(false, "no_match")
    }




    private fun checkScreen(pkg: String, floatingService: FloatingService) {
        val root = rootInActiveWindow ?: return

        bgExecutor.execute {
            dumpClickables(root)
            val allTexts = mutableListOf<String>()
            collectAllTexts(root, allTexts)
            // fullText = entire page → used only for button detection
            val fullText = allTexts.joinToString(" ").lowercase()
            // productText = first 60 nodes only → used for clothing detection
            // avoids false rejects from coupon banners, ads, recommendations at bottom
            val productText = allTexts.take(60).joinToString(" ").lowercase()


            Log.d("Dontry", "=== PRODUCT PAGE DATA ===")
            Log.d("Dontry", "Package: $pkg")
            Log.d("Dontry", "Total text nodes: ${allTexts.size}")
            allTexts.forEachIndexed { index, text ->
                Log.d("Dontry", "[$index] $text")
            }
            Log.d("Dontry", "=== END ===")

            // Buttons can appear anywhere — use full page text
            val foundButtons = mutableSetOf<String>()
            findButtons(root, listOf("add-to-cart-button", "buy-now-button"), foundButtons)

            val hasCart = foundButtons.contains("add-to-cart-button") || CART_BUTTON_SIGNALS.any { fullText.contains(it) }
            val hasBuyNow = foundButtons.contains("buy-now-button") || BUY_NOW_SIGNALS.any { fullText.contains(it) }
            val hasAddToBag = fullText.contains("add to bag")

            val isAmazonPkg   = pkg == "in.amazon.mShop.android.shopping" ||
                    pkg == "com.amazon.mShop.android.shopping" ||
                    pkg == "com.amazon.india.shopping"
            val isFlipkartPkg = pkg == "com.flipkart.android"
            val isMeeshoPkg   = pkg == "com.meesho.supply"
            val isMyntraPkg   = pkg == "com.myntra.android"


            val (isClothing, detectionMethod) = detectClothing(productText)

            val shouldShow = when {
                // Amazon: clothing detection + cart + buy-now
                isAmazonPkg ->
                    isClothing && hasCart && hasBuyNow

                // Flipkart: same logic as Amazon
                isFlipkartPkg ->
                    isClothing && hasCart && hasBuyNow

                // Meesho: buy-now required + clothing
                isMeeshoPkg ->
                    hasBuyNow && isClothing

                // Myntra: add-to-bag required + clothing
                isMyntraPkg ->
                    hasAddToBag && isClothing

                else -> false
            }

            mainHandler.post {
                Log.d("Dontry",
                    "pkg=$pkg isAmazon=$isAmazonPkg " +
                            "isClothing=$isClothing detectionMethod=$detectionMethod " +
                            "hasCart=$hasCart hasBuyNow=$hasBuyNow hasAddToBag=$hasAddToBag " +
                            "shouldShow=$shouldShow lastIsProduct=$lastIsProductPage"
                )

                when {
                    shouldShow -> {
                        cancelPendingRetry()
                        retryCount = 0
                        if (!lastIsProductPage) {
                            Log.d("Dontry", "⚡ Product page confirmed ($detectionMethod) → show icon instantly")
                            cancelPendingHide()
                            floatingService.onAppChanged(pkg, isAmazon = true)
                            lastPackage = pkg
                            lastIsProductPage = true
                        } else {
                            if (hideAlreadyScheduled) {
                                cancelPendingHide()
                                Log.d("Dontry", "✅ Hide cancelled — signals back ($detectionMethod)")
                            }
                        }
                    }

                    !shouldShow && !lastIsProductPage -> {
                        if (retryCount < MAX_RETRIES) {
                            retryCount++
                            Log.d("Dontry", "⏳ Signals not ready — retry $retryCount/$MAX_RETRIES")
                            cancelPendingRetry()
                            pendingRetryRunnable = Runnable {
                                pendingRetryRunnable = null
                                checkScreen(pkg, floatingService)
                            }
                            mainHandler.postDelayed(pendingRetryRunnable!!, RETRY_INTERVAL_MS)
                        } else {
                            Log.d("Dontry", "❌ No signals after retries (last method=$detectionMethod)")
                            retryCount = 0
                        }
                    }

                    else -> {
                        cancelPendingRetry()
                        retryCount = 0
                        if (lastIsProductPage && !hideAlreadyScheduled) {
                            Log.d("Dontry", "⏱ Signals gone — hide in ${HIDE_DELAY_MS}ms")
                            hideAlreadyScheduled = true
                            pendingHideRunnable = Runnable {
                                hideAlreadyScheduled = false
                                bgExecutor.execute {
                                    val freshRoot  = rootInActiveWindow
                                    val freshTexts = mutableListOf<String>()
                                    if (freshRoot != null) collectAllTexts(freshRoot, freshTexts)
                                    val freshText = freshTexts.joinToString(" ").lowercase()
                                    val freshProductText = freshTexts.take(60).joinToString(" ").lowercase()

                                    // Buttons → full page text
                                    val freshFoundButtons = mutableSetOf<String>()
                                    findButtons(freshRoot, listOf("add-to-cart-button", "buy-now-button"), freshFoundButtons)

                                    val freshCart     = freshFoundButtons.contains("add-to-cart-button") || CART_BUTTON_SIGNALS.any { freshText.contains(it) }
                                    val freshBuyNow   = freshFoundButtons.contains("buy-now-button") || BUY_NOW_SIGNALS.any { freshText.contains(it) }
                                    val freshAddToBag = freshText.contains("add to bag")
                                    // Clothing → product area only (first 60 nodes)
                                    val (freshClothing, freshMethod) = detectClothing(freshProductText)

                                    val stillProduct = when {
                                        isAmazonPkg || isFlipkartPkg ->
                                            freshClothing && freshCart && freshBuyNow

                                        isMeeshoPkg ->
                                            freshBuyNow && freshClothing

                                        isMyntraPkg ->
                                            freshAddToBag && freshClothing

                                        else -> false
                                    }

                                    mainHandler.post {
                                        if (stillProduct) {
                                            Log.d("Dontry", "↩️ Signals back after delay ($freshMethod) — keep icon")
                                        } else {
                                            Log.d("Dontry", "✅ Hide confirmed")
                                            floatingService.onAppChanged(pkg, isAmazon = false)
                                            lastIsProductPage = false
                                        }
                                    }
                                }
                            }
                            mainHandler.postDelayed(pendingHideRunnable!!, HIDE_DELAY_MS)
                        }
                    }
                }
            }
        }
    }



    private fun cancelPendingHide() {
        pendingHideRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingHideRunnable = null
        hideAlreadyScheduled = false
    }

    private fun cancelPendingRetry() {
        pendingRetryRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRetryRunnable = null
    }

    private fun cancelAll() {
        cancelPendingHide()
        cancelPendingRetry()
        retryCount = 0
    }


    private fun dumpClickables(node: AccessibilityNodeInfo?) {
        node ?: return
        if (node.isClickable) {
            Log.d("Dontry", "CLICK id=${node.viewIdResourceName} cls=${node.className} text=${node.text} desc=${node.contentDescription}")
        }
        for (i in 0 until node.childCount) {
            dumpClickables(node.getChild(i))
        }
    }


    private fun findButtons(node: AccessibilityNodeInfo?, targetIds: List<String>, found: MutableSet<String>) {
        node ?: return
        val id = node.viewIdResourceName
        if (id != null) {
            for (target in targetIds) {
                if (id.endsWith(target)) {
                    found.add(target)
                }
            }
        }
        // Early exit — stop walking once everything is found
        if (found.size == targetIds.size) return

        for (i in 0 until node.childCount) {
            findButtons(node.getChild(i), targetIds, found)
            if (found.size == targetIds.size) return
        }
    }



    fun getProductTextNodes(limit: Int = 100): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        collectAllTexts(root, texts)
        return texts.take(limit)
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?, result: MutableList<String>) {
        node ?: return
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        if (text.isNotEmpty()) result.add(text.lowercase())
        if (desc.isNotEmpty()) result.add(desc.lowercase())
        for (i in 0 until node.childCount) {
            collectAllTexts(node.getChild(i), result)
        }
    }

    override fun onInterrupt() {}

    // ─────────────────────────────────────────────────────────────
    //  SCREENSHOT
    // ─────────────────────────────────────────────────────────────

    fun captureScreen(onResult: (Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                setCustomKey("model", android.os.Build.MODEL)
                setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                recordException(Exception("Device below API 28 — takeScreenshot unavailable"))
            }
            onResult(null)
            return
        }

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer,
                            screenshot.colorSpace
                        )?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        Log.d("Dontry", "✅ Screenshot SUCCESS")
                        onResult(bitmap)
                    } catch (e: Exception) {
                        com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                            setCustomKey("model", android.os.Build.MODEL)
                            setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                            setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                            recordException(e)
                        }
                        onResult(null)
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.e("Dontry", "❌ Screenshot failed: $errorCode")
                    com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance().apply {
                        setCustomKey("model", android.os.Build.MODEL)
                        setCustomKey("manufacturer", android.os.Build.MANUFACTURER)
                        setCustomKey("android_sdk", android.os.Build.VERSION.SDK_INT)
                        setCustomKey("screenshot_error_code", errorCode)
                        recordException(Exception("takeScreenshot onFailure errorCode=$errorCode"))
                    }
                    onResult(null)
                }
            }
        )
    }
}