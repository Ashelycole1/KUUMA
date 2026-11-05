package ug.kuuma.companion.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import ug.kuuma.contracts.AdviceKey
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Severity

/**
 * A Companion content pack (built from `modules/companion/service/content/pack.yaml`).
 *
 * Every text is looked up as: child's language + age bracket, then that language's default,
 * then English + age bracket, then English default. English is always present (the build checks).
 */
class ContentPack private constructor(private val root: JsonObject) {
    val version: String = root.str("version") ?: error("content pack has no version")
    val format: Int = (root["format"] as? JsonPrimitive)?.intOrNull ?: 0

    /** False until child-safety and counselling reviewers approve every age bracket. */
    val reviewed: Boolean = (root["reviewed"] as? JsonPrimitive)?.booleanOrNull ?: false

    private val cards: Map<AdviceKey, JsonObject> =
        root.arr("advice").filterIsInstance<JsonObject>().associateBy { it.str("key")!! }

    private val crisis = root.arr("crisisCategories").strings().toSet()
    private val noReply = root.arr("noReplyCategories").strings().toSet()

    init {
        require(format == SUPPORTED_FORMAT) { "unsupported content pack format $format" }
    }

    /** Advice keys for a flag, best first, without cards whose minSeverity is above [severity]. */
    fun adviceKeys(category: HarmCategory, severity: Severity): List<AdviceKey> =
        root.obj("byCategory").arr(category.wire).strings().filter { key ->
            val min = cards[key]?.str("minSeverity")?.let(Severity::fromWire) ?: Severity.LOW
            severity.ordinal >= min.ordinal
        }

    fun isCrisis(category: HarmCategory) = category.wire in crisis

    /** True when the keyboard should offer no replies at all, only a nudge not to reply. */
    fun noReply(category: HarmCategory) = category.wire in noReply

    fun hasAdvice(key: AdviceKey) = key in cards

    fun adviceText(key: AdviceKey, lang: Language, bracket: AgeBracket?): String? =
        cards[key]?.get("text").pick(lang, bracket)?.asText()

    fun adviceSteps(key: AdviceKey, lang: Language, bracket: AgeBracket?): List<String> =
        cards[key]?.get("steps").pick(lang, bracket)?.asLines() ?: emptyList()

    fun platformSteps(app: AppSource, action: PlatformAction, lang: Language, bracket: AgeBracket?): List<String> =
        root.obj("platforms").obj(app.wire)[action.wire].pick(lang, bracket)?.asLines() ?: emptyList()

    /** Calm one-tap replies in [lang], or English when that language has none yet. */
    fun safeReplies(lang: Language): List<String> {
        val all = root.obj("safeReplies")
        return (all[lang.wire] as? JsonArray ?: all.arr("en")).strings()
    }

    fun nudge(lang: Language, bracket: AgeBracket?): String = root["nudge"].pick(lang, bracket)?.asText().orEmpty()

    fun crisisText(part: CrisisPart, lang: Language, bracket: AgeBracket?): String =
        root.obj("crisis")[part.wire].pick(lang, bracket)?.asText().orEmpty()

    fun ui(name: String, lang: Language, bracket: AgeBracket?): String =
        root.obj("ui")[name].pick(lang, bracket)?.asText().orEmpty()

    fun helplines(lang: Language, bracket: AgeBracket?): List<Helpline> =
        root.arr("helplines").filterIsInstance<JsonObject>().map {
            Helpline(
                id = it.str("id")!!,
                number = it.str("number")!!,
                free = (it["free"] as? JsonPrimitive)?.booleanOrNull ?: false,
                name = it["name"].pick(lang, bracket)?.asText().orEmpty(),
                hours = it["hours"].pick(lang, bracket)?.asText().orEmpty()
            )
        }

    companion object {
        const val SUPPORTED_FORMAT = 1
        private const val BUNDLED = "kuuma/companion/content-pack.json"

        fun parse(json: String): ContentPack = ContentPack(Json.parseToJsonElement(json) as JsonObject)

        /** The pack shipped inside the app: always available, even offline on first run. */
        fun bundled(): ContentPack {
            val stream = ContentPack::class.java.classLoader!!.getResourceAsStream(BUNDLED)
                ?: error("bundled content pack missing")
            return parse(stream.use { it.readBytes().toString(Charsets.UTF_8) })
        }

        private fun JsonElement?.pick(lang: Language, bracket: AgeBracket?): JsonElement? {
            val byLang = this as? JsonObject ?: return null
            fun from(l: String): JsonElement? {
                val variants = byLang[l] as? JsonObject ?: return null
                return bracket?.let { variants[it.wire] } ?: variants["default"]
            }
            return from(lang.wire) ?: from(Language.EN.wire)
        }

        private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonElement.asLines(): List<String> = (this as? JsonArray)?.strings() ?: emptyList()
        private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.content
        private fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: JsonObject(emptyMap())
        private fun JsonObject.arr(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())
        private fun JsonArray.strings(): List<String> = mapNotNull { (it as? JsonPrimitive)?.content }
    }
}

enum class PlatformAction(val wire: String) { BLOCK("block"), REPORT("report") }

enum class CrisisPart(val wire: String) { TITLE("title"), BODY("body"), BREATHE("breathe"), CALL_PROMPT("callPrompt") }

data class Helpline(val id: String, val number: String, val free: Boolean, val name: String, val hours: String)
