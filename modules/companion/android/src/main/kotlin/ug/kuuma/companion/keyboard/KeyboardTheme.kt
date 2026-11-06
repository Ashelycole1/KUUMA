package ug.kuuma.companion.keyboard

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** The keyboard's colours: the app's soft lavender palette (design-system/kuuma/MASTER.md). */
class KeyboardTheme(val night: Boolean) {
    val background = if (night) rgb(0x16, 0x10, 0x26) else rgb(0xF3, 0xEE, 0xFB)
    val key = if (night) rgb(0x2A, 0x21, 0x40) else Color.WHITE
    val modifier = if (night) rgb(0x22, 0x1A, 0x36) else rgb(0xE4, 0xDA, 0xF7)
    val pressed = if (night) rgb(0x3B, 0x2F, 0x5C) else rgb(0xD9, 0xCC, 0xF5)
    val shadow = if (night) rgb(0x0C, 0x08, 0x16) else rgb(0xD2, 0xC6, 0xEC)
    val text = if (night) rgb(0xED, 0xE9, 0xFE) else rgb(0x2E, 0x10, 0x65)
    val muted = if (night) rgb(0xB8, 0xAE, 0xD6) else rgb(0x5B, 0x54, 0x70)
    val primary = if (night) rgb(0xC4, 0xB5, 0xFD) else rgb(0x7C, 0x3A, 0xED)
    val onPrimary = if (night) rgb(0x1E, 0x10, 0x38) else Color.WHITE
    val divider = if (night) rgb(0x33, 0x29, 0x4D) else rgb(0xE0, 0xD6, 0xF3)

    /** The "try this instead" card: calm, not alarming. */
    val tipBackground = if (night) rgb(0x2B, 0x22, 0x0E) else rgb(0xFF, 0xF4, 0xDB)
    val tipText = if (night) rgb(0xF5, 0xD0, 0x8A) else rgb(0x7F, 0x56, 0x0B)

    companion object {
        fun of(context: Context) = KeyboardTheme(
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        )

        private fun rgb(r: Int, g: Int, b: Int) = Color.rgb(r, g, b)
    }
}
