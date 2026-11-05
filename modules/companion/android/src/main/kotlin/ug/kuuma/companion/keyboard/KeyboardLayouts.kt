package ug.kuuma.companion.keyboard

import ug.kuuma.contracts.Language

/** What a key does. */
enum class KeyKind { CHAR, SHIFT, DELETE, SPACE, ENTER, SYMBOLS, LETTERS, LANGUAGE, SHIELD }

/**
 * One key. [width] is relative (1.0 = a normal letter key). [popup] holds long-press
 * alternatives, e.g. accented letters; the SHIELD key's long press is the quiet SOS instead.
 */
data class Key(
    val label: String,
    val kind: KeyKind = KeyKind.CHAR,
    val width: Float = 1f,
    val popup: List<String> = emptyList()
) {
    val text: String get() = label
}

data class KeyboardLayout(val id: String, val rows: List<List<Key>>)

/**
 * Keyboard layouts. Kept small and plain so the keyboard stays fast on low-end phones.
 *
 * Luganda uses the Latin alphabet plus ŋ (and the digraph "ny", typed as two letters), so its
 * layout is QWERTY with ŋ on the bottom row. Runyankole and Swahili come later and use the same
 * pattern: add a layout here and a word list in resources.
 */
object KeyboardLayouts {
    private fun chars(s: String, popups: Map<Char, String> = emptyMap()): List<Key> =
        s.map { c -> Key(c.toString(), popup = popups[c]?.map { it.toString() } ?: emptyList()) }

    /** 1-9 then 0, as on a phone keyboard (built from a range, not a long digit literal). */
    private val DIGITS = ('1'..'9').joinToString("") + "0"

    private val VOWEL_POPUPS = mapOf(
        'e' to "éè",
        'a' to "áà",
        'o' to "óò",
        'u' to "úù",
        'i' to "íì"
    )

    private fun bottomRow() = listOf(
        Key("?123", KeyKind.SYMBOLS, 1.5f),
        Key("🌐", KeyKind.LANGUAGE),
        Key("🛡", KeyKind.SHIELD),
        Key(" ", KeyKind.SPACE, 4f),
        Key(".", popup = listOf(",", "?", "!", "'")),
        Key("⏎", KeyKind.ENTER, 1.5f)
    )

    val ENGLISH = KeyboardLayout(
        "en",
        listOf(
            chars("qwertyuiop", VOWEL_POPUPS),
            chars("asdfghjkl", VOWEL_POPUPS),
            listOf(Key("⇧", KeyKind.SHIFT, 1.5f)) + chars("zxcvbnm") + Key("⌫", KeyKind.DELETE, 1.5f),
            bottomRow()
        )
    )

    val LUGANDA = KeyboardLayout(
        "lg",
        listOf(
            chars("qwertyuiop", VOWEL_POPUPS),
            chars("asdfghjkl", VOWEL_POPUPS),
            listOf(Key("⇧", KeyKind.SHIFT, 1.5f)) + chars("zxcvbnmŋ", mapOf('n' to "ŋ")) +
                Key("⌫", KeyKind.DELETE, 1.5f),
            bottomRow()
        )
    )

    val SYMBOLS = KeyboardLayout(
        "symbols",
        listOf(
            chars(DIGITS),
            chars("@#%&-+()/"),
            listOf(Key("*", width = 1.5f)) + chars("\"':;!?,") + Key("⌫", KeyKind.DELETE, 1.5f),
            listOf(
                Key("ABC", KeyKind.LETTERS, 1.5f),
                Key("🌐", KeyKind.LANGUAGE),
                Key("🛡", KeyKind.SHIELD),
                Key(" ", KeyKind.SPACE, 4f),
                Key("."),
                Key("⏎", KeyKind.ENTER, 1.5f)
            )
        )
    )

    /** Keyboard languages available now, in switching order (MVP: English and Luganda). */
    val LANGUAGES = listOf(Language.EN, Language.LG)

    fun letters(language: Language): KeyboardLayout = when (language) {
        Language.LG -> LUGANDA
        else -> ENGLISH
    }

    fun next(language: Language): Language = LANGUAGES[(LANGUAGES.indexOf(language) + 1) % LANGUAGES.size]
}
