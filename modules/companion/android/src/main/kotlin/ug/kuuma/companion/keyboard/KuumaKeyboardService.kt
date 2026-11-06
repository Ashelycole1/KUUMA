package ug.kuuma.companion.keyboard

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import ug.kuuma.ai.runtime.DummyReplyRiskChecker
import ug.kuuma.companion.AdvicePlan
import ug.kuuma.companion.CompanionListener
import ug.kuuma.companion.CompanionModule
import ug.kuuma.companion.KeyboardContext
import ug.kuuma.companion.R
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.Severity
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog

/**
 * The Nilinde Keyboard: an everyday keyboard (English and Luganda first) that also
 *  - shows a banner above the keys while the open chat has flags (sentinel.conversation.active),
 *  - checks the draft with the AI build's ReplyRiskChecker and offers a safer wording,
 *  - offers calm one-tap replies, or a nudge not to reply,
 *  - captures the chat with the shield key, and sends a quiet SOS when the shield is held.
 *
 * Privacy: the draft is read only to check it and suggest words, held in memory for that moment,
 * never stored or logged. Password fields get no suggestions and no checks at all.
 * Nothing Nilinde shows here is visible to the other person in the chat.
 */
class KuumaKeyboardService :
    InputMethodService(),
    KeyActionListener,
    CompanionListener {
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { r ->
        Thread(r, "companion-reply-check").apply {
            isDaemon =
                true
        }
    }
    private val fallbackChecker = KindReplyChecker(DummyReplyRiskChecker())
    private val replyCheck = ReplyCheckRunner(
        checker = { CompanionModule.current?.replyChecker ?: fallbackChecker },
        background = background,
        deliver = { main.post(it) }
    )

    private lateinit var keys: KuumaKeyboardView
    private lateinit var banner: TextView
    private lateinit var strip: LinearLayout
    private val theme by lazy { KeyboardTheme.of(this) }

    private var language = Language.EN
    private var shift = Shift.OFF
    private var sensitiveField = false
    private var context: KeyboardContext? = null
    private var risk: ReplyRisk = ReplyCheckRunner.SAFE
    private var flash: String? = null
    private var unsubscribe: (() -> Unit)? = null

    private enum class Shift { OFF, ONCE, LOCK }

    private val checkSoon = Runnable { runChecks() }
    private val clearFlash = Runnable {
        flash = null
        renderStrip()
    }

    private val module: CompanionModule? get() = CompanionModule.current

    override fun onCreate() {
        super.onCreate()
        language = prefs().getString(PREF_LANGUAGE, null)
            ?.let { w -> KeyboardLayouts.LANGUAGES.firstOrNull { it.wire == w } } ?: Language.EN
    }

    override fun onCreateInputView(): View {
        banner = TextView(this).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setOnClickListener { openKuuma() }
        }
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(50)
            setPadding(dp(6), dp(4), dp(6), dp(2))
        }
        keys = KuumaKeyboardView(this).also { it.listener = this }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(theme.background)
            addView(
                banner,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(dp(8), dp(6), dp(8), 0) }
            )
            addView(
                HorizontalScrollView(this@KuumaKeyboardService).apply {
                    isHorizontalScrollBarEnabled = false
                    setBackgroundColor(theme.background)
                    // Lets the word slots share the full width; long reply chips still scroll.
                    isFillViewport = true
                    addView(strip, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(keys, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int = 12, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        sensitiveField = isSensitive(info)
        keys.layout = KeyboardLayouts.letters(language)
        risk = ReplyCheckRunner.SAFE
        if (unsubscribe == null) unsubscribe = module?.addListener(this)
        context = module?.keyboardContext(language)
        updateAutoShift()
        renderBanner()
        scheduleChecks()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        main.removeCallbacks(checkSoon)
        replyCheck.cancel()
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        main.removeCallbacksAndMessages(null)
        background.shutdownNow()
        super.onDestroy()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoShift()
        scheduleChecks()
    }

    // ---- Companion events (bus thread) ---------------------------------------------------

    override fun onKeyboardContext(context: KeyboardContext?) {
        main.post {
            this.context = module?.keyboardContext(language) ?: context
            if (::banner.isInitialized) {
                renderBanner()
                renderStrip()
            }
        }
    }

    override fun onAdvice(plan: AdvicePlan) = Unit // the banner follows onKeyboardContext

    // ---- keys ----------------------------------------------------------------------------

    override fun onKey(key: Key, alternative: String?) {
        val ic = currentInputConnection ?: return
        when (key.kind) {
            KeyKind.CHAR -> {
                val text = alternative ?: if (shift != Shift.OFF) key.text.uppercase() else key.text
                ic.commitText(text, 1)
                if (shift == Shift.ONCE) setShift(Shift.OFF)
            }
            KeyKind.SPACE -> ic.commitText(" ", 1)
            KeyKind.DELETE -> {
                val selected = ic.getSelectedText(0)
                if (selected.isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
            }
            KeyKind.ENTER -> enter()
            KeyKind.SHIFT -> setShift(
                when (shift) {
                    Shift.OFF -> Shift.ONCE
                    Shift.ONCE -> Shift.LOCK
                    Shift.LOCK -> Shift.OFF
                }
            )
            KeyKind.SYMBOLS -> keys.layout = KeyboardLayouts.SYMBOLS
            KeyKind.LETTERS -> keys.layout = KeyboardLayouts.letters(language)
            KeyKind.LANGUAGE -> switchLanguage()
            KeyKind.SHIELD -> capture()
        }
    }

    override fun onLongPress(key: Key): Boolean = when (key.kind) {
        KeyKind.SHIELD -> {
            sos()
            true
        }
        KeyKind.LANGUAGE -> {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            true
        }
        else -> false
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    private fun setShift(value: Shift) {
        shift = value
        keys.shifted = value != Shift.OFF
        keys.capsLock = value == Shift.LOCK
    }

    private fun updateAutoShift() {
        if (!::keys.isInitialized || shift == Shift.LOCK) return
        val info = currentInputEditorInfo ?: return
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        setShift(if (caps != 0 && !sensitiveField) Shift.ONCE else Shift.OFF)
    }

    private fun switchLanguage() {
        language = KeyboardLayouts.next(language)
        prefs().edit().putString(PREF_LANGUAGE, language.wire).apply()
        keys.layout = KeyboardLayouts.letters(language)
        context = module?.keyboardContext(language)
        renderBanner()
        scheduleChecks()
    }

    // ---- shield: capture and quiet SOS ---------------------------------------------------

    private fun capture() {
        val ok = module?.requestCapture() ?: false
        flash(getString(if (ok) R.string.companion_capture_done else R.string.companion_capture_unavailable))
    }

    private fun sos() {
        val m = module ?: return
        m.triggerSos()
        keys.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        flash(getString(R.string.companion_sos_done))
    }

    private fun flash(message: String) {
        flash = message
        renderStrip()
        main.removeCallbacks(clearFlash)
        main.postDelayed(clearFlash, FLASH_MS)
    }

    // ---- suggestions and reply check -----------------------------------------------------

    private fun scheduleChecks() {
        main.removeCallbacks(checkSoon)
        main.postDelayed(checkSoon, CHECK_DEBOUNCE_MS)
    }

    private fun runChecks() {
        if (!::strip.isInitialized) return
        if (sensitiveField) {
            risk = ReplyCheckRunner.SAFE
            renderStrip()
            return
        }
        val draft = currentInputConnection?.getTextBeforeCursor(MAX_DRAFT, 0)?.toString().orEmpty()
        renderStrip(draft)
        replyCheck.submit(draft, language) { result ->
            val wasRisky = risk.risky
            risk = result
            if (result.risky && !wasRisky) {
                SafeLog.i("companion.reply_check.risky", LogField.num("spans", result.spans.size))
            }
            renderStrip(currentInputConnection?.getTextBeforeCursor(MAX_DRAFT, 0)?.toString().orEmpty())
        }
    }

    /** Removes the risky parts of the draft (the text before the cursor). */
    private fun applySaferWording() {
        val ic = currentInputConnection ?: return
        val draft = ic.getTextBeforeCursor(MAX_DRAFT, 0)?.toString() ?: return
        val spans = ReplyCheckRunner.validSpans(risk.spans, draft.length)
        if (spans.isEmpty()) return
        val safer = ReplyCheckRunner.saferWording(draft, spans)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(draft.length, 0)
        ic.commitText(safer, 1)
        ic.endBatchEdit()
        risk = ReplyCheckRunner.SAFE
        SafeLog.i("companion.reply_check.safer_applied")
    }

    // ---- rendering -----------------------------------------------------------------------

    private fun renderBanner() {
        if (!::banner.isInitialized) return
        val ctx = context
        if (ctx == null || ctx.tip.isEmpty()) {
            banner.visibility = View.GONE
            return
        }
        banner.text = "🛡  ${ctx.tip}"
        banner.background = rounded(severityColor(ctx.severity))
        banner.visibility = View.VISIBLE
    }

    private fun renderStrip(draft: String = "") {
        if (!::strip.isInitialized) return
        strip.removeAllViews()
        val pack = module?.advice?.pack
        val bracket = module?.ageBracket
        val f = flash
        val ctx = context
        when {
            f != null -> strip.addView(slot(f, bold = true, color = theme.primary), slotParams())
            risk.risky && pack != null -> {
                val reason = risk.spans.firstOrNull()?.reasonKey ?: risk.suggestionKeys.firstOrNull()
                val why = reason?.let { pack.adviceText(it, language, bracket) }.orEmpty()
                val spans = ReplyCheckRunner.validSpans(risk.spans, draft.length)
                val action = pack.ui("saferWording", language, bracket)
                // Hurtful words: say which words would go ("you are so" alone reads oddly).
                // Personal details: show the draft without them.
                val hurtful = spans.filter { it.reasonKey == KindReplyChecker.BE_KIND }
                    .map { draft.substring(it.start.toInt(), it.end.toInt()) }
                val line = when {
                    spans.isEmpty() -> action
                    hurtful.isNotEmpty() -> getString(
                        R.string.companion_take_out,
                        hurtful.joinToString(", ") { "\u201C$it\u201D" }
                    )
                    else -> getString(R.string.companion_try_instead, ReplyCheckRunner.saferWording(draft, spans))
                }
                strip.addView(saferCard(why, line, action), slotParams())
            }
            ctx != null && draft.isBlank() && pack != null && !sensitiveField -> {
                if (ctx.noReply) {
                    strip.addView(slot(pack.nudge(language, bracket), color = theme.muted), slotParams())
                } else {
                    pack.safeReplies(language).forEach { reply ->
                        strip.addView(pill(reply) { currentInputConnection?.commitText(reply, 1) })
                    }
                }
            }
            !sensitiveField -> {
                val word = currentWord(draft)
                WordList.forLanguage(language).complete(word).forEachIndexed { i, s ->
                    if (i > 0) strip.addView(divider())
                    strip.addView(slot(s, bold = i == 0) { replaceCurrentWord(word, s) }, slotParams())
                }
            }
        }
    }

    /**
     * The kinder wording, shown inside the keyboard: why (one short line) and the reworded draft.
     * One tap puts it in place of what was typed.
     */
    private fun saferCard(why: String, line: String, action: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(theme.tipBackground)
        setPadding(dp(12), dp(5), dp(12), dp(5))
        isClickable = true
        setOnClickListener {
            applySaferWording()
            renderStrip()
        }
        contentDescription = "$why. $action"
        addView(
            TextView(this@KuumaKeyboardService).apply {
                text = if (why.isBlank()) action else "💡 $why"
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(theme.tipText)
                setTypeface(typeface, Typeface.BOLD)
            }
        )
        addView(
            TextView(this@KuumaKeyboardService).apply {
                text = line
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.START
                setTextColor(theme.text)
            }
        )
    }

    private fun slotParams() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)

    private fun divider() = View(this).apply {
        setBackgroundColor(theme.divider)
        layoutParams = LinearLayout.LayoutParams(dp(1), dp(22))
    }

    private fun replaceCurrentWord(typed: String, word: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
    }

    /** A word suggestion (or a short message) sharing the row equally with the others. */
    private fun slot(
        text: String,
        bold: Boolean = false,
        color: Int = theme.text,
        onTap: (() -> Unit)? = null
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
        setTextColor(if (bold) theme.primary else color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        if (onTap != null) {
            isClickable = true
            setOnClickListener { onTap() }
        }
    }

    /** A one-tap calm reply. */
    private fun pill(text: String, onTap: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        maxLines = 1
        setTextColor(theme.text)
        background = rounded(theme.key, radius = 18, stroke = theme.divider)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        layoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), 0, dp(4), 0) }
        isClickable = true
        setOnClickListener { onTap() }
    }

    private fun openKuuma() {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "companion_keyboard"
        private const val PREF_LANGUAGE = "language"
        private const val CHECK_DEBOUNCE_MS = 350L
        private const val FLASH_MS = 2500L
        private const val MAX_DRAFT = 1000

        /** Calm colours: the banner informs, it doesn't alarm. */
        fun severityColor(severity: Severity): Int = when (severity) {
            Severity.LOW -> Color.rgb(0x3D, 0x6F, 0x8E)
            Severity.MEDIUM -> Color.rgb(0x8A, 0x5A, 0x14)
            Severity.HIGH -> Color.rgb(0x9C, 0x3B, 0x3B)
        }

        /** Password and similar fields: no suggestions, no checks, no reading the text. */
        fun isSensitive(info: EditorInfo): Boolean {
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            return (cls == InputType.TYPE_CLASS_TEXT && variation in PASSWORD_VARIATIONS) ||
                (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        }

        private val PASSWORD_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    }
}
