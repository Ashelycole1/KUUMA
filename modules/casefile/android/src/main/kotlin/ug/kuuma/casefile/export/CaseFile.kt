package ug.kuuma.casefile.export

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ug.kuuma.casefile.cases.CaseIndex
import ug.kuuma.casefile.cases.CaseState
import ug.kuuma.casefile.cases.TimelineEntry
import ug.kuuma.casefile.vault.ChainEntry
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.Vault
import ug.kuuma.casefile.vault.VaultItem
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.RecipientType

/** A trusted timestamp of a chain root, from POST /v1/cases/timestamps. */
data class RootTimestamp(val hash: String, val timestamp: String, val signature: String, val authority: String)

/** The exported case: machine-readable JSON and a PDF rendering of the same. */
class CaseFile(val json: JsonObject, val pdf: ByteArray, val root: String)

/**
 * Possible offences, as GUIDANCE ONLY. This mapping is a first draft and must be reviewed
 * by a Ugandan lawyer before the pilot (Casefile done criteria). It is printed as such.
 */
object OffenceGuidance {
    const val DISCLAIMER =
        "Guidance only, not legal advice. Possible offences are suggested automatically and have not yet been " +
            "reviewed by a Ugandan lawyer. A lawyer or the police decide what applies."

    fun forCategory(c: HarmCategory): String = when (c) {
        HarmCategory.INSULT, HarmCategory.HARASSMENT, HarmCategory.SEXUAL_HARASSMENT ->
            "Possible cyber harassment, Computer Misuse Act 2011, section 24."
        HarmCategory.THREAT ->
            "Possible cyber harassment (Computer Misuse Act 2011, s.24) and threatening violence (Penal Code Act)."
        HarmCategory.SEXTORTION, HarmCategory.GROOMING ->
            "Possible cyber harassment (Computer Misuse Act 2011, s.24); if images of a child are involved, " +
                "possibly child pornography offences (s.23). Report to the police."
        HarmCategory.DOXXING ->
            "Possible offences under the Data Protection and Privacy Act 2019 and the Computer Misuse Act."
        HarmCategory.HATE -> "Possible offences under the Computer Misuse (Amendment) Act 2022."
        HarmCategory.SELF_HARM_ENCOURAGEMENT ->
            "No specific offence identified automatically. Seek advice; the child's safety comes first."
    }
}

/** What each recipient sees first. Templates for school, police, platform and lawyer. */
object RecipientTemplates {
    fun intro(r: RecipientType): String = when (r) {
        RecipientType.SCHOOL ->
            "This case file is shared with the school so that it can protect the pupil. It contains messages " +
                "the pupil received, sealed on the pupil's phone so that any change would be detected."
        RecipientType.POLICE ->
            "This case file is submitted as evidence of online abuse. Each exhibit is hashed, signed on the victim's " +
                "device and joined to a hash chain whose root was timestamped by an independent service."
        RecipientType.PLATFORM ->
            "This case file reports abusive content received through your platform, for review under your community " +
                "standards. Sender identities are pseudonymous labels."
        RecipientType.LAWYER ->
            "This case file is shared with counsel. It contains the exhibits, their hashes and signatures, the hash " +
                "chain, its trusted timestamp and the full chain-of-custody log."
    }
}

object CaseFileBuilder {
    const val FORMAT = "kuuma-casefile/1"

    private const val ANONYMOUS_NOTE =
        "Reported by the child's parent or guardian. The child's identity is withheld: only the " +
            "offender's messages are included, and messages that name the child are withheld " +
            "(their hashes stay in the chain)."

    fun build(
        case: CaseState,
        items: List<VaultItem>,
        chain: List<ChainEntry>,
        timeline: List<TimelineEntry>,
        recipient: RecipientType,
        publicKey: String,
        keyProtection: String,
        timestamp: RootTimestamp?,
        generatedAt: Long,
        verifyUrl: String,
        victimIdentity: String? = null,
        translations: Map<String, ExhibitTranslation> = emptyMap(),
        parentReport: ParentReport? = null
    ): CaseFile {
        val caseItems = items.filter { it.caseId == case.caseId && it.kind != ItemKind.CUSTODY }
            .filter { parentReport?.keeps(it, case) ?: true }
        val withheld = caseItems.filter { parentReport?.withholds(it) == true }.map { it.itemId }.toSet()
        val included = caseItems.map { it.itemId }.toSet()
        val shownTimeline = timeline.filter { it.itemId in included }
        val mask: (String) -> String = { parentReport?.mask(it) ?: it }
        val custody = items.filter {
            it.kind == ItemKind.CUSTODY &&
                CaseIndex.parse(it)["caseId"]?.let { v -> (v as? JsonPrimitive)?.content } == case.caseId
        }
        val entryFor = chain.associateBy { it.itemId }
        val sender = SenderDetails.of(case, caseItems)
        val root = chain.lastOrNull()?.chainHash ?: Vault.GENESIS

        val json = buildJsonObject {
            put("format", FORMAT)
            put("caseId", case.caseId)
            put("generatedAt", CaseIndex.iso(generatedAt))
            put("recipientType", recipient.wire)
            put("offenderLabel", case.offenderLabel)
            put(
                "senderDetails",
                buildJsonObject {
                    put("note", "As shown on the child's phone. From the sealed messages; not verified.")
                    put("displayNames", buildJsonArray { sender.displayNames.forEach { add(JsonPrimitive(it)) } })
                    put("phoneNumbers", buildJsonArray { sender.phoneNumbers.forEach { add(JsonPrimitive(it)) } })
                    put("emails", buildJsonArray { sender.emails.forEach { add(JsonPrimitive(it)) } })
                    put("handles", buildJsonArray { sender.handles.forEach { add(JsonPrimitive(it)) } })
                }
            )
            put("victim", victimIdentity?.takeIf { parentReport == null }?.let { JsonPrimitive(it) } ?: JsonNull)
            parentReport?.let { r ->
                put(
                    "report",
                    buildJsonObject {
                        put("reportedBy", "parent")
                        put("anonymous", r.anonymous)
                        if (r.anonymous) {
                            put(
                                "note",
                                ANONYMOUS_NOTE
                            )
                        }
                    }
                )
            }
            put(
                "summary",
                buildJsonObject {
                    put("incidentCount", case.incidentCount)
                    put("topSeverity", case.topSeverity.wire)
                    put("categories", buildJsonArray { case.categories.forEach { add(JsonPrimitive(it.wire)) } })
                    put("firstIncidentAt", case.firstAt?.let { JsonPrimitive(CaseIndex.iso(it)) } ?: JsonNull)
                    put("lastIncidentAt", case.lastAt?.let { JsonPrimitive(CaseIndex.iso(it)) } ?: JsonNull)
                    put("lateNightIncidents", case.lateNightCount())
                    put(
                        "groomingStages",
                        buildJsonArray {
                            case.groomingStages.forEach { add(JsonPrimitive(it.wire)) }
                        }
                    )
                }
            )
            put(
                "guidance",
                buildJsonObject {
                    put("disclaimer", OffenceGuidance.DISCLAIMER)
                    put(
                        "possibleOffences",
                        buildJsonArray {
                            case.categories.forEach { add(JsonPrimitive(OffenceGuidance.forCategory(it))) }
                        }
                    )
                    put("machineTranslation", "Translations, where present, are machine-made and marked as such.")
                }
            )
            put(
                "exhibits",
                JsonArray(
                    caseItems.mapIndexed { i, item ->
                        val e = entryFor.getValue(item.itemId)
                        buildJsonObject {
                            put("exhibit", "E${i + 1}")
                            put("itemId", item.itemId)
                            put("kind", item.kind.name)
                            put("chainIndex", e.index)
                            put("itemHash", e.itemHash)
                            if (item.itemId in withheld) {
                                put("withheld", true) // names the child: hash only, content left out
                            } else {
                                put("content", item.contentJson) // exact sealed bytes: recipients re-hash them
                            }
                        }
                    }
                )
            )
            put(
                "chain",
                buildJsonObject {
                    put("algorithm", "ECDSA-P256-SHA256")
                    put("itemHash", "sha256(\"${Vault.FORMAT}\\n\" + kind + \"\\n\" + content)")
                    put("chainHash", "sha256(prevHash + \"|\" + itemHash + \"|\" + index)")
                    put("publicKey", publicKey)
                    put("keyProtection", keyProtection)
                    put("root", root)
                    put(
                        "links",
                        JsonArray(
                            chain.map { e ->
                                buildJsonObject {
                                    put("index", e.index)
                                    put("itemHash", e.itemHash)
                                    put("prevHash", e.prevHash)
                                    put("chainHash", e.chainHash)
                                    put("signature", e.signature)
                                }
                            }
                        )
                    )
                }
            )
            put(
                "translations",
                JsonArray(
                    caseItems.mapIndexedNotNull { i, item ->
                        translations[item.itemId]?.takeIf { item.itemId !in withheld }?.let { t ->
                            buildJsonObject {
                                put("exhibit", "E${i + 1}")
                                put("itemId", t.itemId)
                                put("from", t.from.wire)
                                put("to", t.to.wire)
                                put("text", mask(t.text))
                                put("machineTranslated", true)
                                put("model", t.model?.let { JsonPrimitive(it) } ?: JsonNull)
                                put("promptVersion", t.promptVersion?.let { JsonPrimitive(it) } ?: JsonNull)
                            }
                        }
                    }
                )
            )
            put("custody", JsonArray(custody.map { JsonPrimitive(it.contentJson) }))
            put(
                "timestamp",
                timestamp?.let {
                    buildJsonObject {
                        put("hash", it.hash)
                        put("timestamp", it.timestamp)
                        put("signature", it.signature)
                        put("authority", it.authority)
                    }
                } ?: JsonNull
            )
            put("verifyUrl", verifyUrl)
        }
        return CaseFile(
            json,
            renderPdf(
                case, recipient, shownTimeline, caseItems, entryFor, custody, root, timestamp, generatedAt, verifyUrl,
                translations, sender, parentReport, withheld
            ),
            root
        )
    }

    private fun languageName(l: Language) = when (l) {
        Language.EN -> "English"
        Language.LG -> "Luganda"
        Language.NYN -> "Runyankole"
        Language.SW -> "Swahili"
    }

    private fun renderPdf(
        case: CaseState,
        recipient: RecipientType,
        timeline: List<TimelineEntry>,
        caseItems: List<VaultItem>,
        entryFor: Map<String, ChainEntry>,
        custody: List<VaultItem>,
        root: String,
        ts: RootTimestamp?,
        generatedAt: Long,
        verifyUrl: String,
        translations: Map<String, ExhibitTranslation>,
        sender: SenderDetails,
        parentReport: ParentReport?,
        withheld: Set<String>
    ): ByteArray {
        val mask: (String) -> String = { parentReport?.mask(it) ?: it }
        val doc = PdfText()
        doc.heading("Kuuma case file")
        doc.line("Case ${case.caseId}  ·  prepared for: ${recipient.wire}  ·  generated ${CaseIndex.iso(generatedAt)}")
        doc.gap()
        if (parentReport != null) {
            doc.para(
                if (parentReport.anonymous) {
                    "$ANONYMOUS_NOTE In this copy the child's name is replaced with [the child]; " +
                        "${withheld.size} message(s) are withheld."
                } else {
                    "Reported by the child's parent or guardian."
                }
            )
            doc.gap()
        }
        doc.para(RecipientTemplates.intro(recipient))
        doc.gap()
        doc.heading("Summary")
        doc.line("Offender: ${case.offenderLabel} (pseudonymous label)")
        doc.line(
            "Incidents: ${case.incidentCount}   Highest severity: ${case.topSeverity.wire}   Late-night incidents: ${case.lateNightCount()}"
        )
        doc.line("Categories: ${case.categories.joinToString { it.wire }.ifEmpty { "none" }}")
        case.firstAt?.let { doc.line("First incident: ${CaseIndex.iso(it)}   Last: ${CaseIndex.iso(case.lastAt!!)}") }
        if (case.groomingStages.isNotEmpty()) {
            doc.line(
                "Grooming stages observed: ${case.groomingStages.joinToString {
                    it.wire
                }}"
            )
        }
        doc.gap()
        doc.heading("Possible offences (guidance)")
        doc.para(OffenceGuidance.DISCLAIMER)
        case.categories.forEach { doc.para("- ${it.wire}: ${OffenceGuidance.forCategory(it)}") }
        doc.gap()
        doc.heading("Sender details (as shown on the child's phone)")
        if (sender.isEmpty) {
            doc.para("The phone showed no name or number for this sender.")
        } else {
            if (sender.displayNames.isNotEmpty()) doc.para("Shown as: ${sender.displayNames.joinToString(", ")}")
            if (sender.phoneNumbers.isNotEmpty()) {
                doc.para(
                    "Phone numbers in their messages: ${sender.phoneNumbers.joinToString(", ")}"
                )
            }
            if (sender.emails.isNotEmpty()) doc.para("Emails in their messages: ${sender.emails.joinToString(", ")}")
            if (sender.handles.isNotEmpty()) doc.para("Handles in their messages: ${sender.handles.joinToString(", ")}")
            doc.para("Taken from the sealed messages below; not verified by Kuuma.")
        }
        doc.gap()
        doc.heading("Timeline")
        val exhibitNo = caseItems.mapIndexed { i, it -> it.itemId to "E${i + 1}" }.toMap()
        for (t in timeline) {
            val who = if (t.fromChild) "Child" else t.senderLabel ?: "?"
            val what = when (t.kind) {
                ItemKind.FLAG -> "FLAGGED ${t.category?.wire} / ${t.severity?.wire}"
                ItemKind.MEDIA -> "[media recorded as hash only; not copied]"
                else -> if (t.itemId in withheld) "[message withheld: it names the child]" else mask(t.text.orEmpty())
            }
            doc.para("${exhibitNo[t.itemId] ?: "-"}  ${CaseIndex.iso(t.at)}  ${t.appSource ?: ""}  $who: $what")
            translations[t.itemId]?.takeIf { t.itemId !in withheld }?.let { tr ->
                val by = listOfNotNull(tr.model, tr.promptVersion).joinToString(", ")
                doc.para(
                    "      Machine translation from ${languageName(
                        tr.from
                    )}${if (by.isEmpty()) "" else " ($by)"}: ${mask(tr.text)}"
                )
            }
        }
        doc.gap()
        doc.heading("Exhibit hashes")
        caseItems.forEachIndexed { i, item ->
            val e = entryFor.getValue(item.itemId)
            doc.line("E${i + 1}  link ${e.index}  sha256 ${e.itemHash}")
        }
        doc.gap()
        doc.heading("Chain of custody")
        if (custody.isEmpty()) doc.line("No views, exports or shares recorded.")
        custody.forEach { doc.para(it.contentJson) }
        doc.gap()
        doc.heading("How to verify")
        doc.para("Chain root: $root")
        doc.para(
            ts?.let { "Timestamped ${it.timestamp} by ${it.authority}." }
                ?: "Not yet timestamped (the phone was offline when this file was made)."
        )
        doc.para(
            "Check this case file at $verifyUrl : the page re-computes every hash, checks every signature and asks the timestamp service about the root."
        )
        return doc.toPdf()
    }
}

/**
 * Encrypts a share with a fresh AES-256-GCM key. The key goes only into the share
 * link's #fragment, which browsers never send to a server, so the cloud holds only
 * ciphertext it cannot read.
 */
object ShareCrypto {
    private val MAGIC = "KCF1".toByteArray(Charsets.US_ASCII)

    class Sealed(val blob: ByteArray, val key: ByteArray)

    fun encrypt(plain: ByteArray, random: SecureRandom = SecureRandom()): Sealed {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(iv)
        out.write(cipher.doFinal(plain))
        return Sealed(out.toByteArray(), key)
    }

    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        require(blob.size > 16 && blob.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a Kuuma share" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(4, 16)))
        return cipher.doFinal(blob.copyOfRange(16, blob.size))
    }

    /** The share container: case JSON plus the PDF, as one JSON document. */
    fun container(file: CaseFile): ByteArray = buildJsonObject {
        put("format", "kuuma-share/1")
        put("case", file.json)
        put("pdf", ug.kuuma.casefile.vault.Base64.encode(file.pdf))
    }.toString().toByteArray(Charsets.UTF_8)
}
