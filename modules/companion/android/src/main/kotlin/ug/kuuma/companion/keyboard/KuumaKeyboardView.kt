package ug.kuuma.companion.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/** What the keyboard view reports. [alternative] is a long-press choice from [Key.popup]. */
interface KeyActionListener {
    fun onKey(key: Key, alternative: String? = null)

    /** A held key: DELETE repeats, SHIELD is the quiet SOS, LANGUAGE opens the keyboard picker. */
    fun onLongPress(key: Key): Boolean = false
}

/**
 * The key grid, drawn on one canvas (no view per key) so it stays light on low-end phones.
 * Works on API 21: no newer drawing APIs.
 */
class KuumaKeyboardView(context: Context) : View(context) {
    var listener: KeyActionListener? = null

    var layout: KeyboardLayout = KeyboardLayouts.ENGLISH
        set(value) {
            field = value
            if (width > 0) computeCells(width)
            requestLayout()
            invalidate()
        }

    /** Shows letters in capitals when shift is on. */
    var shifted: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Shift locked (double tap): draws the shift key highlighted. */
    var capsLock: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowHeight = (52 * density).toInt()
    private val gap = 4 * density
    private val radius = 6 * density
    private val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val bg = if (night) Color.rgb(0x1F, 0x23, 0x26) else Color.rgb(0xE3, 0xE7, 0xEA)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) Color.rgb(0x3A, 0x3F, 0x44) else Color.WHITE
    }
    private val modPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) Color.rgb(0x2C, 0x31, 0x35) else Color.rgb(0xC9, 0xCF, 0xD4)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) Color.rgb(0x55, 0x5B, 0x61) else Color.rgb(0xB0, 0xB8, 0xBF)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x2E, 0x7D, 0x6B) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (night) Color.rgb(0xEE, 0xEE, 0xEE) else Color.rgb(0x21, 0x21, 0x21)
        textAlign = Paint.Align.CENTER
        textSize = 22 * density
    }
    private val smallText = Paint(textPaint).apply { textSize = 15 * density }

    private class Cell(val key: Key, val rect: RectF)

    private var cells: List<Cell> = emptyList()
    private var pressed: Cell? = null
    private var popup: List<Cell> = emptyList()
    private var popupChoice: Cell? = null
    private var longPressed = false

    private val handler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { onHold() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val cell = pressed ?: return
            listener?.onKey(cell.key)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, rowHeight * layout.rows.size + paddingTop + paddingBottom)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = computeCells(w)

    private fun computeCells(w: Int) {
        val out = ArrayList<Cell>()
        val maxUnits = layout.rows.maxOf { row -> row.sumOf { it.width.toDouble() } }.toFloat()
        val unit = w / maxUnits
        layout.rows.forEachIndexed { r, row ->
            val rowUnits = row.sumOf { it.width.toDouble() }.toFloat()
            var x = (w - rowUnits * unit) / 2f // centre short rows (e.g. a-l)
            val top = paddingTop + r * rowHeight.toFloat()
            for (key in row) {
                val kw = key.width * unit
                out += Cell(key, RectF(x + gap / 2, top + gap / 2, x + kw - gap / 2, top + rowHeight - gap / 2))
                x += kw
            }
        }
        cells = out
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(bg)
        for (cell in cells) drawKey(canvas, cell, cell === pressed && popup.isEmpty())
        if (popup.isNotEmpty()) {
            for (cell in popup) drawKey(canvas, cell, cell === popupChoice)
        }
    }

    private fun drawKey(canvas: Canvas, cell: Cell, isPressed: Boolean) {
        val key = cell.key
        val paint = when {
            isPressed -> pressedPaint
            key.kind == KeyKind.SHIFT && (shifted || capsLock) -> accentPaint
            key.kind == KeyKind.CHAR || key.kind == KeyKind.SPACE -> keyPaint
            else -> modPaint
        }
        canvas.drawRoundRect(cell.rect, radius, radius, paint)
        val label = if (key.kind == KeyKind.CHAR && shifted) key.label.uppercase() else key.label
        val p = if (label.length > 1 && key.kind != KeyKind.CHAR) smallText else textPaint
        val y = cell.rect.centerY() - (p.descent() + p.ascent()) / 2
        canvas.drawText(label, cell.rect.centerX(), y, p)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cell = hit(event.x, event.y) ?: return true
                pressed = cell
                longPressed = false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                val hold = if (cell.key.kind == KeyKind.SHIELD) SOS_HOLD_MS else LONG_PRESS_MS
                handler.postDelayed(longPressRunnable, hold)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (popup.isNotEmpty()) {
                popupChoice = popup.minByOrNull { kotlin.math.abs(it.rect.centerX() - event.x) }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                val cell = pressed
                when {
                    cell == null -> Unit
                    popup.isNotEmpty() -> popupChoice?.let { listener?.onKey(cell.key, it.key.label) }
                    !longPressed -> listener?.onKey(cell.key)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                reset()
            }
        }
        return true
    }

    private fun onHold() {
        val cell = pressed ?: return
        longPressed = true
        val key = cell.key
        when {
            key.kind == KeyKind.DELETE -> repeatRunnable.run()
            key.kind == KeyKind.CHAR && key.popup.isNotEmpty() -> showPopup(cell)
            else -> {
                if (listener?.onLongPress(key) == true) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private fun showPopup(anchor: Cell) {
        val w = anchor.rect.width()
        val h = anchor.rect.height()
        val top = (anchor.rect.top - h - gap).coerceAtLeast(paddingTop.toFloat())
        var left = (anchor.rect.left).coerceAtMost(width - anchor.key.popup.size * (w + gap))
        popup = anchor.key.popup.map { alt ->
            Cell(Key(if (shifted) alt.uppercase() else alt), RectF(left, top, left + w, top + h)).also {
                left += w + gap
            }
        }
        popupChoice = popup.first()
        invalidate()
    }

    private fun reset() {
        pressed = null
        popup = emptyList()
        popupChoice = null
        invalidate()
    }

    private fun hit(x: Float, y: Float): Cell? = cells.firstOrNull { it.rect.contains(x, y) }
        ?: cells.minByOrNull {
            val dx = it.rect.centerX() - x
            val dy = it.rect.centerY() - y
            dx * dx + dy * dy
        }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val LONG_PRESS_MS = 400L
        private const val REPEAT_MS = 60L

        /** Long enough that SOS is never sent by a normal tap. */
        const val SOS_HOLD_MS = 1500L
    }
}
