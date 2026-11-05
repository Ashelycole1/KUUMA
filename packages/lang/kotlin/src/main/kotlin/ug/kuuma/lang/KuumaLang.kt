package ug.kuuma.lang

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One value from a kuuma-lang file, e.g. one harm category. */
data class LangEntry(val id: String, val labels: Map<String, String>, val extra: JsonObject) {
    /** Label in [language] (a contract Language wire value), falling back to English. */
    fun label(language: String): String = labels[language] ?: labels.getValue("en")
}

/** Loader for kuuma-lang data (bundled from packages/lang/data by `make gen`). */
object KuumaLang {
    val files = listOf(
        "harm-categories",
        "severity",
        "grooming-stages",
        "age-brackets",
        "recipient-types",
        "languages",
        "advice-keys"
    )

    private val root: JsonObject by lazy {
        val text = KuumaLang::class.java.classLoader!!.getResource("kuuma/lang.json")!!.readText()
        Json.parseToJsonElement(text).jsonObject
    }

    fun entries(file: String): List<LangEntry> {
        require(file in files) { "unknown kuuma-lang file $file" }
        return root.getValue(file).jsonObject.getValue("values").jsonArray.map { el ->
            val obj = el.jsonObject
            val labelObj = (obj["label"] ?: obj["text"])?.jsonObject ?: JsonObject(emptyMap())
            LangEntry(
                id = obj.getValue("id").jsonPrimitive.content,
                labels = labelObj.mapValues { (it.value as JsonPrimitive).content },
                extra = JsonObject(obj.filterKeys { it != "id" && it != "label" })
            )
        }
    }

    fun ids(file: String): List<String> = entries(file).map { it.id }

    fun adviceText(key: String, language: String = "en"): String =
        entries("advice-keys").first { it.id == key }.label(language)

    fun severityRank(severity: String): Int =
        entries("severity").first { it.id == severity }.extra.getValue("rank").jsonPrimitive.content.toInt()
}
