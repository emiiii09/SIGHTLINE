// SPDX-License-Identifier: GPL-3.0-or-later

package com.sightline.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale

/**
 * Sightline: welcome → mandatory API-key setup → analyzer.
 *
 * Identity: light pink (#F4A7C4) brand on pure white / pure black, gray
 * secondary (#71717A), brand typeface (Noto Sans Bold, bundled in assets).
 * The slogan always stays in English.
 *
 * Privacy: every user types their own Meta API key during setup. The key
 * lives in memory only for the session — never persisted, never shipped in
 * the build, never shown in full. The app talks to the Meta Model API
 * directly from the device; there is no backend and no key logging.
 */
class MainActivity : Activity() {

    companion object {
        const val SLOGAN = "Access Beyond Sight."
        val PINK = Color.parseColor("#F4A7C4")
        val WHITE = Color.parseColor("#FFFFFF")
        val BLACK = Color.parseColor("#000000")
        val GRAY = Color.parseColor("#71717A")
        // Brand pink at ~45%: the welcome byline whisper.
        val BYLINE_PINK = Color.parseColor("#73F4A7C4")
    }

    private enum class Screen { WELCOME, SETUP, MENU, ANALYZER, PREVIEW, HELP, ABOUT, HISTORY }

    /** Which single tool the analyzer shows: one screen, one job. */
    private enum class AuditMode { URL, HTML }

    private lateinit var urlInput: EditText
    private lateinit var analyzeButton: Button
    private lateinit var keyField: EditText
    private lateinit var keyStatus: TextView
    private lateinit var forgetKeyButton: Button
    private lateinit var navSettingsBtn: Button
    private lateinit var keyRow: LinearLayout
    private lateinit var settingsKeyError: TextView
    private lateinit var setupKeyField: EditText
    private lateinit var setupEye: EyeToggleView
    private lateinit var setupError: TextView
    private lateinit var setupContinue: Button
    private lateinit var settingsPanel: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var scoreNumber: TextView
    private lateinit var scoreSource: TextView
    private lateinit var scoreCount: TextView
    private lateinit var shareButton: Button
    private lateinit var previewButton: Button
    private lateinit var resultsBox: LinearLayout
    private lateinit var welcomeBox: ScrollView
    private lateinit var setupBox: ScrollView
    private lateinit var welcomeButton: Button
    private lateinit var welcomeBrand: TextView
    private lateinit var welcomeStars: TextView
    private lateinit var welcomeDivider: View
    private var breathing: Boolean = false
    private lateinit var mainBox: LinearLayout
    private lateinit var previewBox: LinearLayout
    private lateinit var helpBox: ScrollView
    private lateinit var aboutBox: ScrollView
    private lateinit var historyBox: ScrollView
    private lateinit var previewWeb: WebView
    private lateinit var verdictBox: LinearLayout
    private lateinit var verdictText: TextView
    private lateinit var verdictButton: Button
    private lateinit var toastView: TextView

    /** Inline fix answers, so reopening a card never pays for the call twice. */
    private val fixCache = mutableMapOf<Int, String>()
    private var lastVerdict = ""
    private lateinit var menuBox: ScrollView
    private lateinit var urlPanel: LinearLayout
    private lateinit var modeTitle: TextView
    private lateinit var contentScroll: ScrollView
    private lateinit var scoreRow: LinearLayout
    private var auditMode: AuditMode = AuditMode.URL
    private lateinit var planButton: Button
    private lateinit var deltaView: TextView
    private lateinit var htmlPanel: LinearLayout
    private lateinit var htmlInput: EditText
    /** Before/after scores; the line itself renders in the current language. */
    private var lastDeltaPrev: Int = -1
    private var lastDeltaScore: Int = -1
    /** Which tool produced the stored results; null until the first audit. */
    private var resultsMode: AuditMode? = null
    /** False when Settings was opened from the menu without picking a tool. */
    private var toolChosen = true
    /** Half-typed keys restored into their fields after a rebuild. */
    private var restoredSetupDraft = ""
    private var restoredSettingsDraft = ""
    private var restoredShowSettings = false
    /** URL kept across rotation; consumed once by the next UI build. */
    private var restoredUrl: String = ""

    private val http = OkHttpClient()
    private val toastHandler = Handler(Looper.getMainLooper())
    private var toastHide: Runnable? = null
    private var lastToastMsg = ""
    private var lastToastAt = 0L

    private var lang: String = "en"
    private var appearance: String = "system"
    private var currentScreen: Screen = Screen.WELCOME
    private var uiStarted: Boolean = false

    /** The user's Meta API key: memory only, cleared when the app restarts. */
    private var sessionKey: String = ""

    private var lastHtml: String = ""
    private var lastIssues: List<Issue> = emptyList()
    private var lastSource: String = ""

    // -- prefs (language, appearance only — never secrets) -------------------

    private fun prefs() = getSharedPreferences("sightline", MODE_PRIVATE)

    /**
     * Encrypted per-user storage for the API key (AndroidX Security, device
     * keystore). Falls back to private storage if the keystore is
     * unavailable, so the app keeps working; data stays app-private.
     */
    private fun securePrefs(): SharedPreferences = try {
        EncryptedSharedPreferences.create(
            this,
            "sightline_keys",
            MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (_: Exception) {
        getSharedPreferences("sightline_keys_plain", MODE_PRIVATE)
    }

    private fun loadStoredKey(): String = try {
        securePrefs().getString("api_key", "").orEmpty()
    } catch (_: Exception) {
        ""
    }

    private fun storeKey(key: String) {
        try {
            securePrefs().edit().putString("api_key", key).apply()
        } catch (_: Exception) {
            // Session still works; the key just won't survive a restart.
        }
    }

    private fun clearStoredKey() {
        try {
            securePrefs().edit().remove("api_key").apply()
        } catch (_: Exception) {
        }
    }

    private fun dark(): Boolean = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun cBg() = if (dark()) BLACK else WHITE
    private fun cSurface() = if (dark()) Color.parseColor("#141417") else Color.parseColor("#F4F4F5")
    private fun cText() = if (dark()) WHITE else BLACK
    private fun cGray() = if (dark()) Color.parseColor("#A8A8B0") else GRAY
    private fun cBorder() = if (dark()) Color.parseColor("#2A2A30") else Color.parseColor("#E4E4E7")
    private fun cSlogan() = if (dark()) WHITE else BLACK

    private fun sevColor(sev: Severity): Int = when (sev) {
        Severity.ERROR -> Color.parseColor("#E5484D")
        Severity.WARNING -> Color.parseColor("#C77414")
        Severity.INFO -> cGray()
    }

    private fun aiClient() = MetaAiClient(apiKey = sessionKey)

    private fun maskedKey(): String =
        if (sessionKey.length >= 8) "••••" + sessionKey.takeLast(4) else "••••"

    private fun s(key: String, vararg args: Any): String {
        val raw = Strings.get(lang, key)
        return if (args.isEmpty()) raw else String.format(raw, *args)
    }

    private fun brandFace(): Typeface =
        Typeface.createFromAsset(assets, "fonts/NotoSans-Bold.ttf")

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun reducedMotion(): Boolean {
        return try {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }

    // -- lifecycle + navigation ----------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lang = prefs().getString("lang", null)
            ?: if (Locale.getDefault().language == "es") "es" else "en"
        appearance = prefs().getString("appearance", "system") ?: "system"
        // Fresh start always lands on welcome; a saved key is loaded so it
        // is only asked once, until the user forgets it in Settings.
        sessionKey = loadStoredKey()
        android.util.Log.d("SightlineState", "CREATE bundleNull=${savedInstanceState == null}")
        if (savedInstanceState != null) restoreSession(savedInstanceState)
        android.util.Log.d("SightlineState", "AFTER screen=$currentScreen source=$lastSource issues=${lastIssues.size} key=${sessionKey.length}")
        buildUi(showSettings = restoredShowSettings)
        restoredShowSettings = false
    }

    /**
     * Rotation (and background kills) keep the session via this bundle,
     * which the system holds in RAM only. A fresh launch restores the key
     * from encrypted storage instead, so it is asked only once.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        android.util.Log.d("SightlineState", "SAVE screen=$currentScreen source=$lastSource issues=${lastIssues.size} key=${sessionKey.length}")
        super.onSaveInstanceState(outState)
        outState.putString("sessionKey", sessionKey)
        outState.putString("lastHtml", lastHtml)
        outState.putString("lastSource", lastSource)
        outState.putString("lastVerdict", lastVerdict)
        outState.putInt("deltaPrev", lastDeltaPrev)
        outState.putInt("deltaScore", lastDeltaScore)
        resultsMode?.let { outState.putString("resultsMode", it.name) }
        outState.putSerializable("lastIssues", ArrayList(lastIssues))
        outState.putSerializable("fixCache", HashMap(fixCache))
        outState.putString("screen", currentScreen.name)
        outState.putString("mode", auditMode.name)
        if (::urlInput.isInitialized) outState.putString("url", urlInput.text.toString())
        if (::setupKeyField.isInitialized) outState.putString("setupDraft", setupKeyField.text.toString())
        if (::keyField.isInitialized) outState.putString("settingsDraft", keyField.text.toString())
        if (::settingsPanel.isInitialized) {
            outState.putBoolean("showSettings", settingsPanel.visibility == View.VISIBLE)
        }
        outState.putBoolean("toolChosen", toolChosen)
    }

    private fun restoreSession(saved: Bundle) {
        sessionKey = saved.getString("sessionKey", "")
        lastHtml = saved.getString("lastHtml", "")
        lastSource = saved.getString("lastSource", "")
        lastVerdict = saved.getString("lastVerdict", "")
        lastDeltaPrev = saved.getInt("deltaPrev", -1)
        lastDeltaScore = saved.getInt("deltaScore", -1)
        resultsMode = saved.getString("resultsMode")?.let {
            runCatching { AuditMode.valueOf(it) }.getOrNull()
        }
        lastIssues = serialList(saved, "lastIssues")
        fixCache.putAll(serialMap(saved, "fixCache"))
        currentScreen = runCatching { Screen.valueOf(saved.getString("screen", "WELCOME")) }
            .getOrDefault(Screen.WELCOME)
        auditMode = runCatching { AuditMode.valueOf(saved.getString("mode", "URL")) }
            .getOrDefault(AuditMode.URL)
        restoredUrl = saved.getString("url", "")
        restoredSetupDraft = saved.getString("setupDraft", "")
        restoredSettingsDraft = saved.getString("settingsDraft", "")
        restoredShowSettings = saved.getBoolean("showSettings", false)
        toolChosen = saved.getBoolean("toolChosen", true)
        uiStarted = true
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun serialList(saved: Bundle, key: String): List<Issue> {
        val raw = if (android.os.Build.VERSION.SDK_INT >= 33) {
            saved.getSerializable(key, ArrayList::class.java)
        } else {
            saved.getSerializable(key) as? ArrayList<*>
        } ?: return emptyList()
        return raw.filterIsInstance<Issue>()
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun serialMap(saved: Bundle, key: String): Map<Int, String> {
        val raw = if (android.os.Build.VERSION.SDK_INT >= 33) {
            saved.getSerializable(key, HashMap::class.java)
        } else {
            saved.getSerializable(key) as? HashMap<*, *>
        } ?: return emptyMap()
        return raw.entries
            .filter { it.key is Int && it.value is String }
            .associate { (it.key as Int) to (it.value as String) }
    }

    /** Shrinks the brand title to exactly one full-width line. */
    /**
     * Largest size that fits SIGHTLINE on one line. Binary search against a
     * real StaticLayout, so letter spacing is measured exactly as rendered.
     * A 6% safety margin absorbs faux-bold, rounding, and density quirks so
     * the brand can never clip at the edges on any device or font scale.
     */
    private fun fitBrand(tv: TextView) {
        val raw = tv.width - tv.paddingLeft - tv.paddingRight
        if (raw <= 0) return
        val available = (raw * 0.94f).toInt()
        val text = "SIGHTLINE"
        val paint = android.text.TextPaint().apply {
            typeface = brandFace()
            letterSpacing = tv.letterSpacing
        }
        var lo = 1f
        var hi = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 120f, resources.displayMetrics,
        )
        repeat(14) {
            val mid = (lo + hi) / 2
            paint.textSize = mid
            val layout = android.text.StaticLayout.Builder
                .obtain(text, 0, text.length, paint, available + 1)
                .setIncludePad(false)
                .build()
            if (layout.lineCount == 1 && layout.getLineWidth(0) <= available) lo = mid
            else hi = mid
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, lo)
    }

    private fun orderOf(screen: Screen): Int = when (screen) {
        Screen.WELCOME -> 0
        Screen.SETUP -> 1
        Screen.MENU -> 2
        Screen.ANALYZER -> 3
        Screen.PREVIEW -> 4
        Screen.HELP -> 5
        Screen.ABOUT -> 6
        Screen.HISTORY -> 7
    }

    private fun viewFor(screen: Screen): View = when (screen) {
        Screen.WELCOME -> welcomeBox
        Screen.SETUP -> setupBox
        Screen.MENU -> menuBox
        Screen.ANALYZER -> mainBox
        Screen.PREVIEW -> previewBox
        Screen.HELP -> helpBox
        Screen.ABOUT -> aboutBox
        Screen.HISTORY -> historyBox
    }

    /**
     * Screen change with a short slide-and-fade. Forward flows glide left,
     * back flows glide right. Instant when reduced motion is on.
     */
    private fun showScreen(screen: Screen, animate: Boolean = true) {
        if (screen != Screen.WELCOME) stopBreathing()
        val previous = currentScreen
        currentScreen = screen
        val target = viewFor(screen)
        val others = listOf(welcomeBox, setupBox, menuBox, mainBox, previewBox, helpBox, aboutBox, historyBox).filter { it != target }
        if (!animate || reducedMotion()) {
            others.forEach { it.visibility = View.GONE }
            target.visibility = View.VISIBLE
            if (screen == Screen.WELCOME) target.post { fitBrand(welcomeBrand) }
            return
        }
        val forward = orderOf(screen) >= orderOf(previous)
        val shift = dp(28).toFloat()
        target.translationX = if (forward) shift else -shift
        target.alpha = 0f
        target.visibility = View.VISIBLE
        target.animate().translationX(0f).alpha(1f).setDuration(280).start()
        for (leaving in others) {
            if (leaving.visibility != View.VISIBLE) continue
            leaving.animate().translationX(if (forward) -shift else shift).alpha(0f)
                .setDuration(180).withEndAction {
                    leaving.visibility = View.GONE
                    leaving.translationX = 0f
                    leaving.alpha = 1f
                }.start()
        }
        // Re-fit on every visit: rebuilds while hidden leave the brand at its
        // initial size, and widths can change (rotation, foldables, windows).
        if (screen == Screen.WELCOME) target.post { fitBrand(welcomeBrand) }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (currentScreen) {
            Screen.PREVIEW -> showScreen(Screen.ANALYZER)
            Screen.HELP -> showScreen(Screen.ANALYZER)
            Screen.ABOUT -> showScreen(Screen.ANALYZER)
            Screen.HISTORY -> showScreen(Screen.MENU)
            Screen.ANALYZER -> showScreen(Screen.MENU)
            Screen.MENU -> showScreen(Screen.WELCOME)
            Screen.SETUP -> showScreen(Screen.WELCOME)
            Screen.WELCOME -> super.onBackPressed()
        }
    }

    // -- compact translucent toast (single style, no duplicates) -------------

    private fun showToast(msg: String) {
        runOnUiThread {
            val now = System.currentTimeMillis()
            if (msg == lastToastMsg && now - lastToastAt < 2500) return@runOnUiThread
            lastToastMsg = msg
            lastToastAt = now
            toastHide?.let { toastHandler.removeCallbacks(it) }
            toastView.text = msg
            toastView.announceForAccessibility(msg)
            toastView.visibility = View.VISIBLE
            if (reducedMotion()) {
                toastView.alpha = 1f
            } else {
                toastView.alpha = 0f
                toastView.animate().alpha(1f).setDuration(120).start()
            }
            toastHide = Runnable {
                if (reducedMotion()) {
                    toastView.visibility = View.GONE
                } else {
                    toastView.animate().alpha(0f).setDuration(250).withEndAction {
                        toastView.visibility = View.GONE
                    }.start()
                }
            }
            toastHandler.postDelayed(toastHide!!, 3000)
        }
    }

    /** Persistent inline error near the field plus one transient toast. */
    private fun fail(inline: TextView?, msg: String) {
        runOnUiThread {
            if (inline != null) {
                inline.text = msg
                inline.visibility = View.VISIBLE
            }
            showToast(msg)
        }
    }

    // -- buttons ---------------------------------------------------------------

    private fun pressFeedback(v: View) {
        v.setOnTouchListener { view, event ->
            if (reducedMotion()) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.96f).scaleY(0.96f).alpha(0.8f)
                    .setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(140).start()
            }
            false
        }
    }

    /** Gentle fade-and-rise entrance. Static when reduced motion is on. */
    private fun animateIn(view: View, delayMs: Long = 0) {
        if (reducedMotion()) return
        view.alpha = 0f
        view.translationY = dp(10).toFloat()
        view.animate().alpha(1f).translationY(0f)
            .setDuration(300).setStartDelay(delayMs).start()
    }

    private fun pill(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(PINK)
        cornerRadius = 999f
    }

    /** Outlined pill: same family as primary, readable on both themes. */
    private fun softBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(cSurface())
        setStroke(dp(2), cBorder())
        cornerRadius = 999f
    }

    private fun roundedBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun primaryBtn(label: String, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            background = pill()
            setTextColor(BLACK)
            setTypeface(null, Typeface.BOLD)
            minimumHeight = dp(56)
            setPadding(dp(24), 0, dp(24), 0)
            pressFeedback(this)
            setOnClickListener { onTap() }
        }
    }

    private fun secondaryBtn(label: String, compact: Boolean = false, onTap: () -> Unit): Button {
        return Button(this).apply {
            text = label
            background = softBg()
            setTextColor(cText())
            setTypeface(null, Typeface.BOLD)
            if (compact) {
                minimumHeight = dp(44)
                textSize = 13f
                setPadding(dp(12), 0, dp(12), 0)
            } else {
                minimumHeight = dp(52)
                setPadding(dp(20), 0, dp(20), 0)
            }
            pressFeedback(this)
            setOnClickListener { onTap() }
        }
    }

    /** Disabled buttons dim so state never relies on behavior alone. */
    private fun setBtnEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.55f
    }

    private fun optionButton(label: String, selected: Boolean, onPick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            if (selected) {
                background = pill()
                setTextColor(BLACK)
                setTypeface(null, Typeface.BOLD)
            } else {
                background = softBg()
                setTextColor(cGray())
            }
            minimumHeight = dp(48)
            pressFeedback(this)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
            setOnClickListener { onPick() }
        }
    }

    // -- welcome (standalone brand cover) --------------------------------------

    private fun buildWelcome(): ScrollView {
        val box = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(cBg())
            setPadding(dp(24), dp(110), dp(24), dp(40))
        }
        welcomeStars = TextView(this).apply {
            text = "✦   ✦   ✦"
            textSize = 15f
            setTextColor(PINK)
            alpha = 0.85f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }
        inner.addView(welcomeStars)
        welcomeBrand = TextView(this).apply {
            text = "SIGHTLINE"
            typeface = brandFace()
            setTextColor(PINK)
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            maxLines = 1
            textSize = 64f
            layoutParams = fullWidth()
        }
        inner.addView(welcomeBrand)
        // Exact fit at any font scale, measured with the real text engine.
        box.post { fitBrand(welcomeBrand) }
        inner.addView(TextView(this).apply {
            text = SLOGAN
            textSize = 19f
            setTypeface(null, Typeface.ITALIC)
            setTextColor(cSlogan())
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, 0)
        })
        welcomeDivider = View(this).apply {
            setBackgroundColor(PINK)
            alpha = 0.55f
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(2)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(22)
            }
        }
        inner.addView(welcomeDivider)
        // One language: the app language, which starts as the device language.
        inner.addView(TextView(this).apply {
            text = s("welcome_desc")
            textSize = 16f
            setTextColor(cText())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.4f)
            setPadding(0, dp(28), 0, dp(52))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            maxWidth = dp(620)
        })
        welcomeButton = primaryBtn("") {
            // Returning with a key in memory skips the setup it already passed.
            showScreen(if (sessionKey.isBlank()) Screen.SETUP else Screen.MENU)
        }
        inner.addView(welcomeButton, fullWidth())
        inner.addView(TextView(this).apply {
            text = "by Emilia Tagliaferro"
            textSize = 11f
            setTextColor(BYLINE_PINK)
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(40))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        box.addView(inner)
        // Staggered entrance, replayed each time the cover is built.
        box.post {
            for (i in 0 until inner.childCount) {
                animateIn(inner.getChildAt(i), (i * 70).toLong())
            }
            twinkleTwice()
            startBreathing()
        }
        return box
    }

    /** Two gentle star pulses. Static when reduced motion is on. */
    private fun twinkleTwice() {
        if (reducedMotion() || !::welcomeStars.isInitialized) return
        welcomeStars.animate().alpha(0.3f).setDuration(650).withEndAction {
            welcomeStars.animate().alpha(0.85f).setDuration(650).withEndAction {
                welcomeStars.animate().alpha(0.35f).setDuration(650).withEndAction {
                    welcomeStars.animate().alpha(0.85f).setDuration(650).start()
                }.start()
            }.start()
        }.start()
    }

    /** Slow glow loop on the divider; stopped when leaving the welcome. */
    private fun startBreathing() {
        if (reducedMotion() || breathing || !::welcomeDivider.isInitialized) return
        breathing = true
        fun beat() {
            if (!breathing) return
            welcomeDivider.animate().alpha(0.9f).setDuration(1100).withEndAction {
                if (!breathing) return@withEndAction
                welcomeDivider.animate().alpha(0.35f).setDuration(1100).withEndAction {
                    beat()
                }.start()
            }.start()
        }
        beat()
    }

    private fun stopBreathing() {
        breathing = false
        if (::welcomeDivider.isInitialized) {
            welcomeDivider.animate().cancel()
            welcomeDivider.alpha = 0.55f
        }
    }

    // -- mandatory key setup -----------------------------------------------------

    private fun buildSetup(): ScrollView {
        val box = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            setPadding(dp(32), dp(48), dp(32), dp(40))
        }
        inner.addView(TextView(this).apply {
            text = s("setup_title")
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(0, 0, 0, dp(20))
        })
        for (key in listOf("setup_what", "setup_why", "setup_how")) {
            inner.addView(TextView(this).apply {
                text = s(key)
                textSize = 14f
                setTextColor(cText())
                setLineSpacing(0f, 1.3f)
                setPadding(0, 0, 0, dp(16))
            })
        }
        inner.addView(TextView(this).apply {
            text = s("apiKey")
            setTextColor(cGray())
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        setupKeyField = EditText(this).apply {
            hint = s("keyHint")
            setHintTextColor(cGray())
            setTextColor(cText())
            background = roundedBg(cSurface(), 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = HeartsTransformation
        }
        setupEye = EyeToggleView(this).apply {
            contentDescription = s("show")
            isFocusable = true
            setOnClickListener { flipKeyVisibility(setupEye, setupKeyField) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                leftMargin = dp(8)
            }
        }
        row.addView(setupKeyField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(setupEye)
        inner.addView(row)
        setupError = TextView(this).apply {
            textSize = 13f
            setTextColor(sevColor(Severity.ERROR))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        inner.addView(setupError)
        setupContinue = primaryBtn("") {
            onContinueSetup()
        }
        val params = fullWidth()
        params.topMargin = dp(28)
        inner.addView(setupContinue, params)
        val skipParams = fullWidth()
        skipParams.topMargin = dp(12)
        inner.addView(secondaryBtn(s("skip")) { onSkipSetup() }, skipParams)
        box.addView(inner)
        return box
    }

    /** Entering without a key is allowed, but the error notice always shows. */
    private fun onSkipSetup() {
        sessionKey = ""
        clearStoredKey()
        setupKeyField.setText("")
        updateKeyStatus()
        showScreen(Screen.MENU)
        showToast(s("needKey"))
    }

    /** Flips one key field between hearts and plain letters. */
    private fun flipKeyVisibility(eye: EyeToggleView, field: EditText) {
        val show = !eye.open
        eye.open = show
        eye.contentDescription = s(if (show) "hide" else "show")
        val cursor = field.selectionStart
        field.transformationMethod = if (show) null else HeartsTransformation
        field.setSelection(cursor.coerceIn(0, field.text.length))
        eye.announceForAccessibility(eye.contentDescription)
    }

    private fun setSetupBusy(busy: Boolean) {
        setBtnEnabled(setupContinue, !busy)
        setupContinue.text = s(if (busy) "verifying" else "continue")
    }

    private fun onContinueSetup() {
        setupError.visibility = View.GONE
        val typed = setupKeyField.text.toString().trim()
        if (typed.isEmpty()) {
            if (sessionKey.isNotBlank()) {
                showScreen(Screen.MENU)
                return
            }
            fail(setupError, s("t_keyEmpty"))
            return
        }
        setSetupBusy(true)
        MetaAiClient(apiKey = typed).validateKey(typed) { result ->
            runOnUiThread {
                setSetupBusy(false)
                result
                    .onSuccess {
                        sessionKey = typed
                        storeKey(typed)
                        setupKeyField.setText("")
                        showToast(s("t_configOk"))
                        updateKeyStatus()
                        showScreen(Screen.MENU)
                    }
                    .onFailure {
                        fail(setupError, s(MetaAiClient.validationFailureKey(it.message.orEmpty())))
                    }
            }
        }
    }

    // -- main UI -------------------------------------------------------------------

    // -- menu: one card per tool, nothing else to parse ---------------------------

    private fun buildMenu(): ScrollView {
        val box = ScrollView(this).apply { isFillViewport = true }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        inner.addView(TextView(this).apply {
            text = "✦   ✦   ✦"
            textSize = 16f
            setTextColor(PINK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        inner.addView(TextView(this).apply {
            text = "SIGHTLINE"
            textSize = 15f
            typeface = brandFace()
            setTextColor(PINK)
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = s("menuTitle")
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        })
        val gap = { v: View ->
            inner.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(16) },
            )
        }
        gap(menuCard(s("menuUrlT"), s("menuUrlD")) { openAnalyzer(AuditMode.URL) })
        gap(menuCard(s("menuHtmlT"), s("menuHtmlD")) { openAnalyzer(AuditMode.HTML) })
        inner.addView(
            secondaryBtn(s("settings"), compact = true) { openSettings() },
            fullWidth(),
        )
        inner.addView(
            secondaryBtn(s("historyTitle"), compact = true) {
                refreshHistory()
                showScreen(Screen.HISTORY)
            },
            fullWidth().apply { topMargin = dp(12) },
        )
        box.addView(inner)
        return box
    }

    /** Big tappable card: title plus one plain line of what it does. */
    private fun menuCard(title: String, desc: String, onTap: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 24)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            minimumHeight = dp(132)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $desc"
            val head = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(this@MainActivity).apply {
                text = "✦ "
                textSize = 18f
                setTextColor(PINK)
            })
            head.addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText())
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(head)
            addView(TextView(this@MainActivity).apply {
                text = desc
                textSize = 13f
                setTextColor(cGray())
                setPadding(0, dp(4), 0, 0)
            })
            pressFeedback(this)
            setOnClickListener { onTap() }
        }
    }

    /** Enters the analyzer showing a single tool. */
    private fun openAnalyzer(mode: AuditMode, showSettings: Boolean = false) {
        auditMode = mode
        toolChosen = true
        modeTitle.text = s(if (mode == AuditMode.URL) "menuUrlT" else "menuHtmlT")
        applyToolVisibility()
        // Tools open clean: settings only when asked from the menu.
        settingsPanel.visibility = if (showSettings) View.VISIBLE else View.GONE
        // Results belong to the last audit only: the other tool opens clean
        // until it audits (a new audit always replaces the stored results).
        if (hasStoredResults()) {
            showStoredResults()
            contentScroll.post { contentScroll.scrollTo(0, scoreRow.top) }
        } else {
            hideStoredResults()
            contentScroll.post { contentScroll.scrollTo(0, 0) }
        }
        showScreen(Screen.ANALYZER)
    }

    /** Settings from the menu: no tool picked, so only settings show. */
    private fun openSettings() {
        toolChosen = false
        applyToolVisibility()
        showScreen(Screen.ANALYZER)
        contentScroll.post { contentScroll.scrollTo(0, 0) }
    }

    /**
     * Tool UI shows only after picking a card in the menu. Without a tool,
     * the title, panels and results stay hidden and ‹ Menu goes full width.
     */
    private fun applyToolVisibility() {
        if (toolChosen) {
            modeTitle.visibility = View.VISIBLE
            urlPanel.visibility = if (auditMode == AuditMode.URL) View.VISIBLE else View.GONE
            htmlPanel.visibility = if (auditMode == AuditMode.HTML) View.VISIBLE else View.GONE
            resultsBox.visibility = View.VISIBLE
            navSettingsBtn.visibility = View.VISIBLE
            // Score, verdict, plan and delta are shown by showStoredResults
            // (or a fresh audit) and hidden by hideStoredResults.
        } else {
            modeTitle.visibility = View.GONE
            urlPanel.visibility = View.GONE
            htmlPanel.visibility = View.GONE
            scoreRow.visibility = View.GONE
            verdictBox.visibility = View.GONE
            planButton.visibility = View.GONE
            deltaView.visibility = View.GONE
            resultsBox.visibility = View.GONE
            navSettingsBtn.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
        }
    }

    private fun hasStoredResults(): Boolean =
        resultsMode == auditMode && (lastIssues.isNotEmpty() || lastSource.isNotEmpty())

    private fun recordHistory(source: String, score: Int, count: Int) {
        val entries = History.add(
            History.parse(prefs().getString("history", null)),
            History.Entry(System.currentTimeMillis(), source, score, count),
        )
        prefs().edit().putString("history", History.format(entries)).apply()
    }

    private fun deltaText(): String? =
        if (lastDeltaPrev < 0) null
        else Report.deltaLine(lang, lastDeltaPrev, lastDeltaScore)

    private fun showStoredResults() {
        scoreRow.visibility = View.VISIBLE
        scoreSource.text = lastSource
        scoreNumber.text = "${AccessibilityAnalyzer.score(lastIssues)}/100"
        scoreCount.text = s("findingsFmt", lastIssues.size)
        renderIssues()
        if (lastVerdict.isNotEmpty()) {
            verdictText.text = lastVerdict
            verdictText.visibility = View.VISIBLE
        } else {
            verdictText.text = ""
            verdictText.visibility = View.GONE
        }
        verdictBox.visibility = View.VISIBLE
        planButton.visibility = View.VISIBLE
        val delta = deltaText()
        if (delta != null) {
            deltaView.text = delta
            deltaView.visibility = View.VISIBLE
        } else {
            deltaView.visibility = View.GONE
        }
    }

    private fun hideStoredResults() {
        scoreRow.visibility = View.GONE
        verdictBox.visibility = View.GONE
        planButton.visibility = View.GONE
        deltaView.visibility = View.GONE
        resultsBox.removeAllViews()
    }

    private fun buildUi(keepUrl: String = "", showSettings: Boolean = false) {
        val resume = currentScreen
        val keepHtml = if (::htmlInput.isInitialized) htmlInput.text.toString() else ""
        val urlNow = if (restoredUrl.isNotEmpty()) restoredUrl.also { restoredUrl = "" } else keepUrl
        val root = FrameLayout(this).apply {
            setBackgroundColor(cBg())
        }
        val screens = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Slim bar stays fixed; the whole page below scrolls as one.
        contentScroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentScroll.addView(content)
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(cBg())
            setPadding(0, 0, 0, dp(8))
        }
        navRow.addView(
            secondaryBtn(s("menuBack"), compact = true) { showScreen(Screen.MENU) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(12) },
        )
        navSettingsBtn = secondaryBtn(s("settings"), compact = true) {
            settingsPanel.visibility =
                if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        navRow.addView(
            navSettingsBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        screens.addView(navRow)
        screens.addView(
            contentScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f,
            ),
        )
        content.addView(TextView(this).apply {
            text = s("title")
            textSize = 24f
            typeface = brandFace()
            setTextColor(PINK)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(TextView(this).apply {
            text = s("subtitle")
            textSize = 13f
            setTextColor(cGray())
            gravity = Gravity.CENTER
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        content.addView(View(this).apply {
            setBackgroundColor(PINK)
            alpha = 0.35f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2),
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(4)
            }
        })
        modeTitle = TextView(this).apply {
            text = s(if (auditMode == AuditMode.URL) "menuUrlT" else "menuHtmlT")
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(0, dp(4), 0, 0)
        }
        content.addView(modeTitle)

        settingsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = if (showSettings) View.VISIBLE else View.GONE
        }
        settingsPanel.addView(label(s("language")))
        val langRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        langRow.addView(optionButton("Español", lang == "es") { setLang("es") })
        langRow.addView(optionButton("English", lang == "en") { setLang("en") })
        settingsPanel.addView(langRow)
        settingsPanel.addView(label(s("appearance")))
        val appRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        appRow.addView(optionButton(s("light"), appearance == "light") { setAppearance("light") })
        appRow.addView(optionButton(s("dark"), appearance == "dark") { setAppearance("dark") })
        appRow.addView(optionButton(s("system"), appearance == "system") { setAppearance("system") })
        settingsPanel.addView(appRow)
        settingsPanel.addView(label(s("apiKey")))
        keyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        keyField = EditText(this).apply {
            hint = s("keyHint")
            setHintTextColor(cGray())
            setTextColor(cText())
            background = roundedBg(cBorder(), 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = HeartsTransformation
        }
        val settingsEye = EyeToggleView(this).apply {
            contentDescription = s("show")
            isFocusable = true
            setOnClickListener { flipKeyVisibility(this, keyField) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                leftMargin = dp(8)
            }
        }
        val saveKeyButton = primaryBtn(s("save")) { saveSessionKey() }
        keyRow.addView(keyField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(settingsEye)
        keyRow.addView(saveKeyButton)
        settingsPanel.addView(keyRow)
        keyStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(cGray())
        }
        settingsPanel.addView(keyStatus)
        forgetKeyButton = secondaryBtn(s("forgetKey")) { forgetKey() }
        forgetKeyButton.visibility = if (sessionKey.isBlank()) View.GONE else View.VISIBLE
        settingsPanel.addView(
            forgetKeyButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        settingsKeyError = TextView(this).apply {
            textSize = 13f
            setTextColor(sevColor(Severity.ERROR))
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        settingsPanel.addView(settingsKeyError)
        settingsPanel.addView(TextView(this).apply {
            text = s("keyHelp")
            setTextColor(cGray())
            textSize = 12f
        })
        val aboutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        aboutRow.addView(
            secondaryBtn(s("aboutBtn")) { showScreen(Screen.ABOUT) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(12) },
        )
        aboutRow.addView(
            secondaryBtn(s("helpBtn")) { showScreen(Screen.HELP) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        settingsPanel.addView(
            aboutRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        content.addView(settingsPanel)
        updateKeyStatus()

        urlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (auditMode == AuditMode.URL) View.VISIBLE else View.GONE
        }
        urlPanel.addView(label(s("urlLabel")))
        urlInput = EditText(this).apply {
            hint = s("urlHint")
            setHintTextColor(cGray())
            setTextColor(cText())
            background = roundedBg(cSurface(), 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(urlNow)
        }
        urlPanel.addView(urlInput, fullWidth())
        analyzeButton = primaryBtn(s("analyze")) {
            fetchAndAnalyze(urlInput.text.toString().trim())
        }
        urlPanel.addView(
            analyzeButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        urlPanel.addView(
            secondaryBtn(s("menuDemoT")) { loadDemoAsset() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        content.addView(urlPanel)
        htmlPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (auditMode == AuditMode.HTML) View.VISIBLE else View.GONE
        }
        htmlPanel.addView(label(s("htmlLabel")))
        htmlInput = EditText(this).apply {
            hint = s("htmlHint")
            setHintTextColor(cGray())
            setTextColor(cText())
            background = roundedBg(cSurface(), 12)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            // Long pastes scroll inside the field instead of burying
            // the buttons and results below the fold.
            maxLines = 8
            isVerticalScrollBarEnabled = true
            gravity = Gravity.TOP
        }
        htmlPanel.addView(htmlInput, fullWidth())
        htmlInput.setText(keepHtml)
        // The field lives inside the page scroll: it consumes its own
        // vertical gestures instead of fighting the parent for them.
        htmlInput.setOnTouchListener { v, event ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        val htmlAudit = primaryBtn(s("htmlAudit")) { auditPastedHtml() }
        htmlPanel.addView(
            htmlAudit,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        htmlPanel.addView(
            secondaryBtn(s("menuDemoT")) { loadDemoIntoHtml() },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        content.addView(htmlPanel)

        progress = ProgressBar(this).apply { visibility = ProgressBar.GONE }
        content.addView(progress)

        // Score card: source on top, big number + count, delta, action halves.
        scoreRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 20)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        scoreSource = TextView(this).apply {
            textSize = 12f
            setTextColor(cGray())
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        scoreRow.addView(scoreSource)
        // Stacked: big number above the count, never pushed off at huge fonts.
        scoreNumber = TextView(this).apply {
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setSingleLine(true)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        scoreRow.addView(scoreNumber)
        scoreCount = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cGray())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        scoreRow.addView(scoreCount)
        deltaView = TextView(this).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cGray())
            visibility = View.GONE
            setPadding(0, dp(2), 0, 0)
        }
        scoreRow.addView(deltaView)
        val scoreBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        shareButton = secondaryBtn(s("share")) { shareReport() }
        previewButton = secondaryBtn(s("preview")) { openPreview() }
        scoreBtns.addView(
            shareButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(12) },
        )
        scoreBtns.addView(
            previewButton,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        scoreRow.addView(
            scoreBtns,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(scoreRow)

        verdictBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            visibility = View.GONE
        }
        verdictBox.addView(TextView(this).apply {
            text = "✦ " + s("verdictTitle")
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
        })
        verdictButton = primaryBtn(s("aiAnalyze")) { requestVerdict() }
        verdictBox.addView(
            verdictButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        verdictText = TextView(this).apply {
            textSize = 14f
            setTextColor(cText())
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        verdictBox.addView(verdictText)
        content.addView(verdictBox)
        planButton = secondaryBtn(s("planBtn")) { copyPlan() }
        planButton.visibility = View.GONE
        content.addView(
            planButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(resultsBox)

        mainBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
        }
        while (screens.childCount > 0) {
            val child = screens.getChildAt(0)
            screens.removeViewAt(0)
            mainBox.addView(child)
        }

        welcomeBox = buildWelcome()
        setupBox = buildSetup()
        menuBox = buildMenu()
        previewBox = buildPreview()
        helpBox = buildHelp()
        aboutBox = buildAbout()
        historyBox = buildHistory()
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
        }
        holder.addView(welcomeBox)
        holder.addView(setupBox)
        holder.addView(mainBox)
        holder.addView(
            menuBox,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        holder.addView(previewBox)
        holder.addView(helpBox)
        holder.addView(aboutBox)
        holder.addView(historyBox)
        root.addView(
            holder,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        toastView = TextView(this).apply {
            textSize = 14f
            setPadding(dp(20), dp(12), dp(20), dp(12))
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                if (dark()) {
                    setColor(Color.argb(225, 255, 255, 255))
                    setTextColor(BLACK)
                } else {
                    setColor(Color.argb(225, 20, 20, 26))
                    setTextColor(WHITE)
                }
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val toastParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply {
            bottomMargin = dp(48)
            leftMargin = dp(24)
            rightMargin = dp(24)
        }
        root.addView(toastView, toastParams)

        welcomeButton.text = s("btn_start")
        setupContinue.text = s("continue")
        setupEye.open = false
        setupEye.contentDescription = s("show")
        setContentView(root)
        // Fresh start always lands on welcome; rebuilds keep the current screen.
        showScreen(if (uiStarted) resume else Screen.WELCOME, animate = false)
        uiStarted = true
        if (toolChosen && hasStoredResults()) {
            showStoredResults()
            // Land where the user was: results were auto-scrolled into view.
            if (currentScreen == Screen.ANALYZER) {
                contentScroll.post { contentScroll.scrollTo(0, scoreRow.top) }
            }
        }
        applyToolVisibility()
        if (currentScreen == Screen.PREVIEW && lastHtml.isNotEmpty()) openPreview()
        setupKeyField.setText(restoredSetupDraft)
        keyField.setText(restoredSettingsDraft)
        restoredSetupDraft = ""
        restoredSettingsDraft = ""
    }

    private fun label(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(cGray())
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun setLang(code: String) {
        if (lang == code) return
        lang = code
        prefs().edit().putString("lang", code).apply()
        buildUi(keepUrl = urlInput.text.toString(), showSettings = true)
        // Findings carry their language: re-analyze instead of re-showing
        // stale results from the previous language.
        if (lastHtml.isNotEmpty() && resultsMode != null) {
            if (resultsMode == auditMode && toolChosen) {
                runAudit(lastHtml, lastSource)
            } else {
                // Other tool's results stay hidden, but refresh them silently
                // so they come back in the new language (AI texts are dropped:
                // they can't be re-rendered without a new call).
                lastIssues = AccessibilityAnalyzer.analyze(lastHtml, lang)
                fixCache.clear()
                lastVerdict = ""
            }
        }
    }

    private fun setAppearance(mode: String) {
        if (appearance == mode) return
        appearance = mode
        prefs().edit().putString("appearance", mode).apply()
        buildUi(keepUrl = urlInput.text.toString(), showSettings = true)
    }

    // -- session key (validated before use, kept in encrypted storage) --------

    private fun saveSessionKey() {
        val typed = keyField.text.toString().trim()
        settingsKeyError.visibility = View.GONE
        if (typed.isEmpty()) {
            fail(settingsKeyError, s("t_keyEmpty"))
            return
        }
        MetaAiClient(apiKey = typed).validateKey(typed) { result ->
            runOnUiThread {
                result
                    .onSuccess {
                        sessionKey = typed
                        storeKey(typed)
                        keyField.setText("")
                        updateKeyStatus()
                        showToast(s("t_configOk"))
                        renderIssues()
                    }
                    .onFailure {
                        // Old key stays: a bad key never locks the app.
                        fail(settingsKeyError, s(MetaAiClient.validationFailureKey(it.message.orEmpty())))
                    }
            }
        }
    }

    private fun updateKeyStatus() {
        keyStatus.text = if (sessionKey.isBlank()) {
            s("keyStatusEmpty")
        } else {
            s("keyStatusSet", maskedKey())
        }
        if (::forgetKeyButton.isInitialized) {
            forgetKeyButton.visibility = if (sessionKey.isBlank()) View.GONE else View.VISIBLE
        }
        // No point showing the input when a key is saved: masked status
        // plus Forget stay; forgetting reveals the input again.
        if (::keyRow.isInitialized) {
            keyRow.visibility = if (sessionKey.isBlank()) View.VISIBLE else View.GONE
        }
    }

    /** Deletes the key from memory and from encrypted storage. */
    private fun forgetKey() {
        sessionKey = ""
        clearStoredKey()
        keyField.setText("")
        updateKeyStatus()
        showToast(s("t_keyForgot"))
    }

    // -- audit flow ----------------------------------------------------------

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        setBtnEnabled(analyzeButton, !busy)
        analyzeButton.text = s(if (busy) "analyzing" else "analyze")
    }

    private fun loadDemoAsset() {
        setBusy(true)
        Thread {
            val html = try {
                assets.open("demo.html").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                ""
            }
            runOnUiThread {
                setBusy(false)
                if (html.isEmpty()) {
                    showToast(s("demoFail"))
                } else {
                    runAudit(html, "demo")
                }
            }
        }.start()
    }

    /** Fills the HTML field with the sample page and audits it. */
    private fun loadDemoIntoHtml() {
        Thread {
            val html = try {
                assets.open("demo.html").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                ""
            }
            runOnUiThread {
                if (html.isEmpty()) {
                    showToast(s("demoFail"))
                } else {
                    htmlInput.setText(html)
                    runAudit(html, "demo")
                }
            }
        }.start()
    }

    private fun fetchAndAnalyze(url: String) {
        if (url.isBlank()) {
            showToast(s("enterUrl"))
            urlInput.requestFocus()
            return
        }
        val target = if (url.startsWith("http")) url else "https://$url"
        setBusy(true)
        http.newCall(Request.Builder().url(target).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    setBusy(false)
                    // The URL stays in the field so the user can retry.
                    showToast(s("t_analysisFail"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val html = response.use { it.body?.string().orEmpty() }
                runOnUiThread {
                    setBusy(false)
                    if (html.isEmpty()) showToast(s("emptyResp", target))
                    else runAudit(html, target)
                }
            }
        })
    }

    private fun runAudit(html: String, source: String) {
        val newIssues = AccessibilityAnalyzer.analyze(html, lang)
        val score = AccessibilityAnalyzer.score(newIssues)
        // Before/after only for genuinely new input: language-change re-runs
        // keep the delta they already had instead of comparing with themselves.
        if (source != lastSource || html != lastHtml) {
            val prev = prefs().getInt(prevScoreKey(source), -1)
            if (prev < 0) {
                lastDeltaPrev = -1
                lastDeltaScore = -1
            } else {
                lastDeltaPrev = prev
                lastDeltaScore = score
            }
            prefs().edit().putInt(prevScoreKey(source), score).apply()
            recordHistory(source, score, newIssues.size)
        }
        resultsMode = auditMode
        lastHtml = html
        lastSource = source
        lastIssues = newIssues
        animateScore(source, score, lastIssues.size)
        scoreRow.visibility = View.VISIBLE
        shareButton.visibility = View.VISIBLE
        previewButton.visibility = View.VISIBLE
        planButton.visibility = View.VISIBLE
        val delta = deltaText()
        if (delta != null) {
            deltaView.text = delta
            deltaView.visibility = View.VISIBLE
        } else {
            deltaView.visibility = View.GONE
        }
        fixCache.clear()
        lastVerdict = ""
        verdictText.visibility = View.GONE
        verdictText.text = ""
        setBtnEnabled(verdictButton, true)
        verdictButton.text = s("aiAnalyze")
        verdictBox.visibility = View.VISIBLE
        renderIssues()
        // Fresh results scroll into view instead of hiding below the fold.
        contentScroll.post {
            if (reducedMotion()) contentScroll.scrollTo(0, scoreRow.top)
            else contentScroll.smoothScrollTo(0, scoreRow.top)
        }
    }

    /** Help screen: step-by-step guide plus tappable FAQ. Opened from Settings. */
    private fun buildHelp(): ScrollView {
        val box = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(secondaryBtn("‹ " + s("back")) { showScreen(Screen.ANALYZER) })
        topRow.addView(TextView(this).apply {
            text = s("helpTitle")
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        inner.addView(topRow)
        inner.addView(TextView(this).apply {
            text = s("about")
            textSize = 13f
            setTextColor(cGray())
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(8), 0, 0)
        })
        inner.addView(sectionHeader(s("helpSteps")))
        for (i in 1..5) {
            inner.addView(helpStep(i, s("hstep$i")))
        }
        inner.addView(sectionHeader(s("helpChecks")))
        for (i in 1..6) {
            inner.addView(TextView(this).apply {
                text = s("hchk${i}t")
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText())
                setPadding(0, dp(8), 0, 0)
            })
            inner.addView(TextView(this).apply {
                text = s("hchk${i}d")
                textSize = 13f
                setTextColor(cGray())
                setPadding(0, 0, 0, dp(2))
            })
        }
        inner.addView(sectionHeader(s("helpFaq")))
        for (i in 1..6) {
            inner.addView(faqItem(s("hq$i"), s("ha$i")))
        }
        box.addView(inner)
        return box
    }

    /** One guide step: pink number badge plus the instruction. */
    private fun helpStep(number: Int, text: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                this.text = number.toString()
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(BLACK)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(PINK)
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(12)
                }
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 14f
                setTextColor(cText())
                setLineSpacing(0f, 1.3f)
                // Weighted rows can measure a hair short and slice the last
                // line's descenders; bottom padding keeps glyphs whole.
                setPadding(0, 0, 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    /** One FAQ card: the question row taps open, the answer expands below it. */
    private fun faqItem(question: String, answer: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 16)
            setPadding(dp(16), dp(4), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val answerView = TextView(this).apply {
            text = answer
            textSize = 14f
            setTextColor(cText())
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        val chevron = TextView(this).apply {
            text = "▸"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(PINK)
            setPadding(dp(12), 0, 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            isClickable = true
            isFocusable = true
            contentDescription = question
            addView(TextView(this@MainActivity).apply {
                text = question
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(chevron)
            pressFeedback(this)
            setOnClickListener {
                val open = answerView.visibility != View.VISIBLE
                answerView.visibility = if (open) View.VISIBLE else View.GONE
                chevron.text = if (open) "▾" else "▸"
                if (open) answerView.announceForAccessibility(answer)
            }
        }
        card.addView(header)
        card.addView(answerView)
        return card
    }

    /** About screen: mission, goals, how it works, version. Opened from Settings. */
    private fun buildAbout(): ScrollView {
        val box = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(secondaryBtn("‹ " + s("back")) { showScreen(Screen.ANALYZER) })
        topRow.addView(TextView(this).apply {
            text = s("aboutTitle")
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        inner.addView(topRow)
        inner.addView(TextView(this).apply {
            text = "SIGHTLINE"
            typeface = brandFace()
            textSize = 30f
            letterSpacing = 0.12f
            setTextColor(PINK)
            gravity = Gravity.CENTER
            maxLines = 1
            // Shrinks instead of clipping on huge font sizes.
            setAutoSizeTextTypeUniformWithConfiguration(
                dp(18), dp(30), 1, android.util.TypedValue.COMPLEX_UNIT_PX,
            )
            setPadding(0, dp(16), 0, 0)
        })
        inner.addView(TextView(this).apply {
            text = SLOGAN
            textSize = 14f
            setTypeface(null, Typeface.ITALIC)
            setTextColor(cSlogan())
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = "✦   ✦   ✦"
            textSize = 14f
            setTextColor(PINK)
            alpha = 0.85f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        })
        inner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = fullWidth().apply { topMargin = dp(8) }
            addView(TextView(this@MainActivity).apply {
                text = s("aboutMission")
                textSize = 15f
                setTextColor(cText())
                setLineSpacing(0f, 1.35f)
            })
        })
        inner.addView(sectionHeader(s("aboutObjT")))
        for (i in 1..4) {
            inner.addView(starRow(s("aboutObj$i")))
        }
        inner.addView(sectionHeader(s("aboutHowT")))
        for (i in 1..3) {
            inner.addView(starRow(s("aboutHow$i")))
        }
        inner.addView(View(this).apply {
            setBackgroundColor(PINK)
            alpha = 0.35f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(2),
            ).apply {
                topMargin = dp(24)
                bottomMargin = dp(12)
            }
        })
        inner.addView(TextView(this).apply {
            text = s("appVersion", appVersionName())
            textSize = 12f
            setTextColor(cGray())
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = s("aboutLicense")
            textSize = 12f
            setTextColor(cGray())
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(this).apply {
            text = "by Emilia Tagliaferro"
            textSize = 11f
            setTextColor(BYLINE_PINK)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        box.addView(inner)
        return box
    }

    /** History screen: recent scores, newest first. Opened from the menu. */
    private fun buildHistory(): ScrollView {
        return ScrollView(this).apply { addView(historyInner()) }
    }

    /** Rebuilt on every open so it never shows a stale list. */
    private fun refreshHistory() {
        historyBox.removeAllViews()
        historyBox.addView(historyInner())
    }

    private fun historyInner(): LinearLayout {
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            setPadding(dp(24), dp(24), dp(24), dp(40))
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(secondaryBtn("‹ " + s("back")) { showScreen(Screen.MENU) })
        topRow.addView(TextView(this).apply {
            text = s("historyTitle")
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        inner.addView(topRow)
        val entries = History.parse(prefs().getString("history", null))
        if (entries.isEmpty()) {
            inner.addView(TextView(this).apply {
                text = s("historyEmpty")
                textSize = 14f
                setTextColor(cGray())
                setLineSpacing(0f, 1.3f)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
            })
        } else {
            val dateFmt = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
            entries.forEachIndexed { i, entry ->
                if (i > 0) {
                    inner.addView(View(this).apply {
                        setBackgroundColor(cBorder())
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(1),
                        )
                    })
                }
                inner.addView(historyRow(entry, dateFmt.format(java.util.Date(entry.time))))
            }
        }
        return inner
    }

    /** One history line: pink score, source plus date, findings count. */
    private fun historyRow(entry: History.Entry, date: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(TextView(this@MainActivity).apply {
                text = entry.score.toString()
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(PINK)
                gravity = Gravity.CENTER
                minWidth = dp(52)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(12)
                    marginEnd = dp(8)
                }
                addView(TextView(this@MainActivity).apply {
                    text = entry.source
                    textSize = 14f
                    setTextColor(cText())
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(this@MainActivity).apply {
                    text = date
                    textSize = 12f
                    setTextColor(cGray())
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = s("findingsFmt", entry.count)
                textSize = 12f
                setTextColor(cGray())
            })
        }
    }

    /** One bulleted line: pink star plus the text. */
    private fun starRow(text: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(6), 0, dp(6))
            addView(TextView(this@MainActivity).apply {
                this.text = "✦"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(PINK)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            })
            addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 14f
                setTextColor(cText())
                setLineSpacing(0f, 1.3f)
                // Same guard as the help steps: keep descenders whole.
                setPadding(0, 0, 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** Full preview screen: the marked page fills the view. */
    private fun buildPreview(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), 0)
        }
        topRow.addView(secondaryBtn("‹ " + s("back")) { showScreen(Screen.ANALYZER) })
        val title = TextView(this).apply {
            text = s("preview")
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(dp(12), 0, 0, 0)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(title)
        box.addView(topRow)
        box.addView(TextView(this).apply {
            text = s("previewLegend")
            textSize = 13f
            setTextColor(cGray())
            setPadding(dp(16), dp(8), dp(16), dp(8))
        })
        previewWeb = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f,
            )
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // No wide-viewport overview: pages without a viewport meta tag
            // (like the demo) would shrink to an unreadable size.
            // Follow the system font size so large-text users get large pages.
            settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(50, 200)
        }
        box.addView(previewWeb)
        return box
    }

    private fun openPreview() {
        if (lastHtml.isEmpty()) return
        val marked = AccessibilityAnalyzer.highlight(lastHtml, lang)
        val base = if (lastSource.startsWith("http")) lastSource else null
        previewWeb.loadDataWithBaseURL(base, marked, "text/html", "utf-8", null)
        showScreen(Screen.PREVIEW)
    }

    /** Counts the score up from zero. Instant when reduced motion is on. */
    private fun animateScore(source: String, score: Int, count: Int) {
        scoreSource.text = source
        scoreCount.text = s("findingsFmt", count)
        scoreNumber.announceForAccessibility(s("score", source, score, count))
        if (reducedMotion()) {
            scoreNumber.text = "$score/100"
            return
        }
        ValueAnimator.ofInt(0, score).apply {
            duration = 650
            addUpdateListener {
                scoreNumber.text = "${it.animatedValue as Int}/100"
            }
            start()
        }
    }

    private fun renderIssues() {
        resultsBox.removeAllViews()
        if (lastIssues.isEmpty()) {
            if (lastSource.isNotEmpty()) {
                resultsBox.addView(TextView(this).apply {
                    text = s("noIssues")
                    setTextColor(cGray())
                })
            }
            return
        }
        var shown = 0
        val (errors, rest) = lastIssues.partition { it.severity == Severity.ERROR }
        if (errors.isNotEmpty()) {
            resultsBox.addView(sectionHeader(s("confirmed")))
            for (issue in errors) {
                val card = issueCard(issue)
                resultsBox.addView(card)
                if (shown < 8) animateIn(card, (shown * 60).toLong())
                shown++
            }
        }
        if (rest.isNotEmpty()) {
            resultsBox.addView(sectionHeader(s("possible")))
            for (issue in rest) {
                val card = issueCard(issue)
                resultsBox.addView(card)
                if (shown < 8) animateIn(card, (shown * 60).toLong())
                shown++
            }
        }
        resultsBox.addView(TextView(this).apply {
            text = s("limits")
            setTextColor(cGray())
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            val raw = "✦ $text"
            val styled = SpannableString(raw)
            styled.setSpan(
                ForegroundColorSpan(PINK), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            this.text = styled
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cText())
            setPadding(0, dp(12), 0, dp(4))
        }
    }

    private fun issueCard(issue: Issue): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(cSurface(), 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, dp(12)) }
        card.layoutParams = params
        val sevLabel = when (issue.severity) {
            Severity.ERROR -> s("sev_error")
            Severity.WARNING -> s("sev_warning")
            Severity.INFO -> s("sev_info")
        }
        card.addView(TextView(this).apply {
            text = "#${issue.marker} · $sevLabel · ${issue.type}"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(sevColor(issue.severity))
        })
        card.addView(TextView(this).apply {
            text = issue.description
            setTextColor(cText())
        })
        card.addView(TextView(this).apply {
            text = issue.element
            setTextColor(cGray())
            textSize = 12f
        })
        card.addView(TextView(this).apply {
            text = s("baseline", issue.suggestion)
            setTextColor(cGray())
            textSize = 13f
        })
        val hasKey = sessionKey.isNotBlank()
        val fixButton = Button(this).apply {
            text = s("fix")
            if (hasKey) {
                background = pill()
                setTextColor(BLACK)
            } else {
                background = softBg()
                setTextColor(cGray())
            }
            pressFeedback(this)
        }
        if (!hasKey) {
            card.addView(TextView(this).apply {
                text = s("needKey")
                setTextColor(cGray())
                textSize = 12f
            })
        }
        card.addView(fixButton)
        val fixContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(fixContainer)
        fixButton.setOnClickListener { toggleFix(issue, fixButton, fixContainer) }
        return card
    }

    /** Expands the fix inside the card; cached answers reopen instantly. */
    private fun toggleFix(issue: Issue, button: Button, container: LinearLayout) {
        // An error panel retries on the next tap instead of just closing.
        if (container.visibility == View.VISIBLE && container.tag != "error") {
            container.visibility = View.GONE
            return
        }
        if (sessionKey.isBlank()) {
            settingsPanel.visibility = View.VISIBLE
            fail(settingsKeyError, s("needKey"))
            return
        }
        val cached = fixCache[issue.marker]
        if (cached != null) {
            showFixAnswer(container, cached)
            return
        }
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = s("asking")
            setTextColor(cGray())
            textSize = 13f
        })
        container.visibility = View.VISIBLE
        setBtnEnabled(button, false)
        Thread {
            aiClient().suggestFix(issue, lastHtml, lang) { result ->
                runOnUiThread {
                    setBtnEnabled(button, true)
                    result
                        .onSuccess {
                            if (it.isBlank()) {
                                showFixError(container, s("aiEmpty"))
                                showToast(s("aiEmpty"))
                            } else {
                                fixCache[issue.marker] = it
                                showFixAnswer(container, it)
                            }
                        }
                        .onFailure { e ->
                            if ("401" in e.message.orEmpty() || "403" in e.message.orEmpty()) {
                                container.visibility = View.GONE
                                container.tag = null
                                // The app stays open; the key can be fixed in Settings.
                                settingsKeyError.text = s("keyInvalid")
                                settingsKeyError.visibility = View.VISIBLE
                                settingsPanel.visibility = View.VISIBLE
                                showToast(s("keyInvalid"))
                            } else {
                                showFixError(container, s("aiNetFail"))
                                showToast(s("aiNetFail"))
                            }
                        }
                }
            }
        }.start()
    }

    private fun showFixAnswer(container: LinearLayout, answer: String) {
        container.tag = null
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = answer
            setTextColor(cText())
            textSize = 13f
            setLineSpacing(0f, 1.3f)
        })
        container.addView(secondaryBtn(s("copy")) { copyToClipboard(answer) })
        container.visibility = View.VISIBLE
    }

    private fun showFixError(container: LinearLayout, msg: String) {
        container.tag = "error"
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = msg
            setTextColor(sevColor(Severity.ERROR))
            textSize = 13f
            setLineSpacing(0f, 1.3f)
        })
        container.visibility = View.VISIBLE
    }

    private fun copyToClipboard(text: String, toastKey: String = "copied") {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("sightline", text))
        showToast(s(toastKey))
    }

    private fun copyPlan() {
        if (lastSource.isEmpty()) return
        val text = Report.buildPlan(
            lang, lastSource, AccessibilityAnalyzer.score(lastIssues),
            lastIssues, lastVerdict, fixCache,
        )
        copyToClipboard(text, "planCopied")
    }

    private fun auditPastedHtml() {
        val html = htmlInput.text.toString()
        if (html.isBlank()) {
            showToast(s("htmlEmpty"))
            htmlInput.requestFocus()
            return
        }
        htmlPanel.visibility = View.GONE
        runAudit(html, s("htmlSource"))
    }

    private fun prevScoreKey(source: String) = "prev_score_$source"

    private fun requestVerdict() {
        if (sessionKey.isBlank()) {
            settingsPanel.visibility = View.VISIBLE
            fail(settingsKeyError, s("needKey"))
            return
        }
        setBtnEnabled(verdictButton, false)
        verdictButton.text = s("asking")
        verdictText.visibility = View.GONE
        Thread {
            aiClient().analyzePage(lastSource, lastIssues, lang) { result ->
                runOnUiThread {
                    setBtnEnabled(verdictButton, true)
                    verdictButton.text = s("aiAnalyze")
                    result
                        .onSuccess {
                            if (it.isBlank()) {
                                verdictText.text = s("aiEmpty")
                                verdictText.visibility = View.VISIBLE
                                showToast(s("aiEmpty"))
                            } else {
                                lastVerdict = it
                                verdictText.text = it
                                verdictText.visibility = View.VISIBLE
                            }
                        }
                        .onFailure { e ->
                            if ("401" in e.message.orEmpty() || "403" in e.message.orEmpty()) {
                                settingsKeyError.text = s("keyInvalid")
                                settingsKeyError.visibility = View.VISIBLE
                                settingsPanel.visibility = View.VISIBLE
                                showToast(s("keyInvalid"))
                            } else {
                                verdictText.text = s("aiNetFail")
                                verdictText.visibility = View.VISIBLE
                                showToast(s("aiNetFail"))
                            }
                        }
                }
            }
        }.start()
    }

    private fun shareReport() {
        if (lastSource.isEmpty()) return
        val text = Report.build(lang, lastSource, AccessibilityAnalyzer.score(lastIssues), lastIssues)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, s("share")))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    /** Layout for a button inside a horizontal row, with a gap to its neighbor. */
    private fun rowChild(withGap: Boolean) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        if (withGap) marginEnd = dp(12)
    }

}
