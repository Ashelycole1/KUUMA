package ug.kuuma.casefile.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import ug.kuuma.casefile.cases.CaseIndex
import ug.kuuma.casefile.cases.CaseState
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.VaultItem
import ug.kuuma.core.redact.BasicRedactor

/**
 * Who sent the harmful messages, as the child's phone showed them: the sender's display name
 * (a saved name or the number, as the messaging app shows it) and any phone numbers, emails
 * and handles written in their messages. Only the offender's own messages count, never the
 * child's or friends'. This goes in the case file only, which the child (with a parent's
 * approval) chooses to share; it never leaves the phone otherwise, and is never translated
 * or sent to a model.
 */
data class SenderDetails(
    val displayNames: List<String>,
    val phoneNumbers: List<String>,
    val emails: List<String>,
    val handles: List<String>
) {
    val isEmpty get() = displayNames.isEmpty() && phoneNumbers.isEmpty() && emails.isEmpty() && handles.isEmpty()

    companion object {
        fun of(case: CaseState, caseItems: List<VaultItem>): SenderDetails {
            val redactor = BasicRedactor()
            val names = linkedSetOf<String>()
            val found = mapOf("PHONE" to linkedSetOf<String>(), "EMAIL" to linkedSetOf(), "HANDLE" to linkedSetOf())
            for (item in caseItems.filter { it.kind == ItemKind.MESSAGE }) {
                val c = CaseIndex.parse(item)
                val fromChild = c["fromChild"]?.jsonPrimitive?.booleanOrNull == true
                val sender = (c["senderRef"] as? JsonPrimitive)?.content
                if (fromChild || sender !in case.senderRefs) continue
                (c["senderDisplayName"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }
                    ?.let { names += it.content.trim() }
                val text = (c["text"] as? JsonPrimitive)?.content ?: continue
                redactor.redact(text).replacements.forEach { (placeholder, original) ->
                    found[placeholder.removePrefix("[").substringBefore("_")]?.add(original.trim())
                }
            }
            return SenderDetails(
                names.toList(),
                found.getValue("PHONE").toList(),
                found.getValue("EMAIL").toList(),
                found.getValue("HANDLE").toList()
            )
        }
    }
}
