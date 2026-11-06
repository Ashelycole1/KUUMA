package ug.kuuma.casefile.export

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import ug.kuuma.casefile.cases.CaseIndex
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.VaultItem
import ug.kuuma.casefile.vault.VaultStore
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.TranslateRequest
import ug.kuuma.contracts.TranslateResponse
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.redact.BasicRedactor
import ug.kuuma.core.redact.Redactor

/** POST /v1/ai/translate. Sunflower can take seconds per message. */
fun interface Translator {
    fun translate(request: TranslateRequest): TranslateResponse
}

/**
 * A machine translation of one exhibit, kept beside the evidence and never inside it: the
 * sealed original stays the exhibit, and every translation is labelled machine-made.
 */
@Serializable
data class ExhibitTranslation(
    val itemId: String,
    val from: Language,
    val to: Language,
    val text: String,
    val model: String? = null,
    val promptVersion: String? = null
)

/**
 * Translates a case's non-English messages before export. Text leaves the phone redacted
 * (names, numbers, emails, handles as placeholders); the names are put back here, on the phone.
 * Results are kept in vault meta, so a later export (even offline) reuses them.
 */
class CaseTranslator(
    private val store: VaultStore,
    private val translator: Translator?,
    private val redactor: Redactor = BasicRedactor(),
    private val maxPerExport: Int = 40
) {
    fun cached(itemId: String): ExhibitTranslation? =
        store.meta("tr.$itemId")?.let { ContractJson.json.decodeFromString(ExhibitTranslation.serializer(), it) }

    /** Translations for [caseItems], fetching the missing ones while online. Never throws. */
    fun forCase(caseItems: List<VaultItem>): Map<String, ExhibitTranslation> {
        val out = LinkedHashMap<String, ExhibitTranslation>()
        val flagLanguage = caseItems.filter { it.kind == ItemKind.FLAG }.associate { item ->
            item.flagId to CaseIndex.parse(item)["language"]?.jsonPrimitive?.content?.let(::languageOrNull)
        }
        val names = caseItems.filter { it.kind == ItemKind.MESSAGE }
            .mapNotNull {
                (CaseIndex.parse(it)["senderDisplayName"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }
            .toSet()
        var fetched = 0
        var online = translator != null
        for (item in caseItems.filter { it.kind == ItemKind.MESSAGE }) {
            cached(item.itemId)?.let {
                out[item.itemId] = it
                continue
            }
            val from = flagLanguage[item.flagId] ?: continue
            if (from == Language.EN || !online || fetched >= maxPerExport) continue
            val text =
                (CaseIndex.parse(item)["text"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: continue
            val redacted = redactor.redact(text, names)
            try {
                val r = translator!!.translate(TranslateRequest(redacted.text.take(4000), from, Language.EN))
                fetched++
                if (r.machineTranslated == false) continue // same language: nothing to add
                var restored = r.text
                redacted.replacements.forEach { (placeholder, original) ->
                    restored =
                        restored.replace(placeholder, original)
                }
                val t = ExhibitTranslation(item.itemId, from, Language.EN, restored, r.model, r.promptVersion)
                store.setMeta("tr.${item.itemId}", ContractJson.json.encodeToString(ExhibitTranslation.serializer(), t))
                out[item.itemId] = t
            } catch (e: IOException) {
                online = false // offline or the model is busy: the rest wait for the next export
                SafeLog.w("casefile.translate.unavailable", LogField.num("done", fetched))
            }
        }
        return out
    }

    private fun languageOrNull(wire: String) = Language.entries.firstOrNull { it.wire == wire }
}
