package ug.kuuma.casefile.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import ug.kuuma.casefile.cases.CaseIndex
import ug.kuuma.casefile.cases.CaseState
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.VaultItem

/**
 * A case file the parent asked for. When [anonymous], everything that could identify the child
 * is left out:
 *  - no victim identity;
 *  - only the offender's messages (never the child's replies or friends' messages);
 *  - an offender message that names the child is "withheld": its hash stays in the signed chain
 *    (so the file still verifies) but its content is not in the file;
 *  - in the readable timeline, the child's names are replaced with "[the child]".
 * The names come from the parent (sealed with the family key) and never leave the phone.
 */
data class ParentReport(val anonymous: Boolean, val childNames: List<String> = emptyList()) {
    private val patterns = childNames.map { it.trim() }.filter { it.length >= 2 }.distinct().map {
        Regex("(?<![\\p{L}])" + Regex.escape(it) + "(?![\\p{L}])", RegexOption.IGNORE_CASE)
    }

    /** Is this item in the report? */
    fun keeps(item: VaultItem, case: CaseState): Boolean {
        if (!anonymous || item.kind != ItemKind.MESSAGE) return true
        val c = CaseIndex.parse(item)
        val fromChild = c["fromChild"]?.jsonPrimitive?.booleanOrNull == true
        return !fromChild && (c["senderRef"] as? JsonPrimitive)?.content in case.senderRefs
    }

    /** Does this item's content name the child? Then only its hash goes in the file. */
    fun withholds(item: VaultItem): Boolean = anonymous && patterns.any { it.containsMatchIn(item.contentJson) }

    fun mask(text: String): String =
        if (!anonymous) text else patterns.fold(text) { t, re -> re.replace(t, "[the child]") }
}
