package ug.kuuma.casefile

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ug.kuuma.casefile.cases.CaseIndex
import ug.kuuma.casefile.cases.CaseState
import ug.kuuma.casefile.cases.Patterns
import ug.kuuma.casefile.cases.TimelineEntry
import ug.kuuma.casefile.export.CaseFile
import ug.kuuma.casefile.export.CaseFileBuilder
import ug.kuuma.casefile.export.CaseTranslator
import ug.kuuma.casefile.export.ParentReport
import ug.kuuma.casefile.export.RootTimestamp
import ug.kuuma.casefile.export.ShareCrypto
import ug.kuuma.casefile.export.Translator
import ug.kuuma.casefile.vault.ChainEntry
import ug.kuuma.casefile.vault.ChainReport
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.ItemSigner
import ug.kuuma.casefile.vault.Vault
import ug.kuuma.casefile.vault.VaultStore
import ug.kuuma.contracts.BundleRequest
import ug.kuuma.contracts.CasefileCaseEscalatedPayload
import ug.kuuma.contracts.CasefileCaseUpdatedPayload
import ug.kuuma.contracts.CasefileEvidenceSealedPayload
import ug.kuuma.contracts.CasefileReportReadyPayload
import ug.kuuma.contracts.CasefileShareRequestedPayload
import ug.kuuma.contracts.EscalationReason
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GuardianReportRequestedPayload
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.ReportOutcome
import ug.kuuma.contracts.SentinelCaptureReadyPayload
import ug.kuuma.contracts.SentinelFlagUpdatedPayload
import ug.kuuma.contracts.SentinelMessageFlaggedPayload
import ug.kuuma.contracts.ShareRequest
import ug.kuuma.contracts.ShareStatus
import ug.kuuma.core.bus.ModulePublisher
import ug.kuuma.core.crypto.Base64Url
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog

/** /v1/cases calls the vault makes. HTTP implementation: [HttpCasesApi]. */
interface CasesApi {
    fun timestamp(hash: String): RootTimestamp
    fun createBundle(caseId: String, request: BundleRequest): ug.kuuma.contracts.BundleResponse
    fun upload(url: String, blob: ByteArray)
    fun createShare(caseId: String, request: ShareRequest): ug.kuuma.contracts.ShareResponse
}

/** Who did something, for the custody log. Never a name. */
enum class Actor { SYSTEM, CHILD, PARENT, ADULT_USER }

/** State of one share request, kept in vault meta. The link (with its key) never leaves the phone except to the recipient. */
data class ShareRecord(
    val requestId: String,
    val caseId: String,
    val recipient: RecipientType,
    val status: String,
    val link: String?
)

enum class DeletionResult { DELETED, NEEDS_PARENT_APPROVAL }

/**
 * Casefile's phone side, free of Android so it is unit-tested end to end. Not
 * thread-safe by design: the module calls it from one background thread.
 */
class CasefileEngine(
    private val publisher: ModulePublisher,
    private val store: VaultStore,
    private val signer: ItemSigner,
    private val api: CasesApi? = null,
    private val shareBaseUrl: String = "http://localhost:8080/verify",
    private val clock: () -> Long = System::currentTimeMillis,
    translator: Translator? = null
) {
    val vault = Vault(store, signer, clock)
    private val translations = CaseTranslator(store, translator)

    // ------------------------------------------------------------------ intake

    fun onFlagged(p: SentinelMessageFlaggedPayload) {
        if (store.meta("flag.${p.flagId}") != null) return
        val caseId = caseFor(p.senderRef)
        store.setMeta("flag.${p.flagId}", caseId)
        val now = clock()
        val content = buildJsonObject {
            put("flagId", p.flagId)
            put("messageRef", p.messageRef)
            put("appSource", p.appSource.wire)
            put("conversationRef", p.conversationRef)
            put("senderRef", p.senderRef)
            put("category", p.category.wire)
            put("severity", p.severity.wire)
            put("confidence", p.confidence)
            put("language", p.language.wire)
            put("flaggedAt", CaseIndex.iso(now))
            put("flaggedAtMillis", now.toString())
        }
        sealAndAnnounce(ItemKind.FLAG, caseId, p.flagId, content)
        caseChanged(caseId)
    }

    fun onFlagUpdated(p: SentinelFlagUpdatedPayload) {
        val caseId = store.meta("flag.${p.flagId}") ?: return
        val content = buildJsonObject {
            put("flagId", p.flagId)
            put("category", p.category.wire)
            put("severity", p.severity.wire)
            put("groomingSignals", buildJsonArray { p.groomingSignals.forEach { add(JsonPrimitive(it.wire)) } })
            put("dismissed", p.dismissed == true)
            put("at", CaseIndex.iso(clock()))
        }
        sealAndAnnounce(ItemKind.FLAG_UPDATE, caseId, p.flagId, content)
        caseChanged(caseId)
    }

    /** Captured threads: each message is sealed once (captures of the same chat overlap). */
    fun onCapture(p: SentinelCaptureReadyPayload) {
        val caseId = store.meta("flag.${p.flagId}")
            ?: p.messages.firstOrNull { !it.fromChild }?.let { caseFor(it.senderRef) }
            ?: return
        store.setMeta("flag.${p.flagId}", caseId)
        val app = appOf(p.flagId)
        var added = false
        for (m in p.messages) {
            if (store.meta("msg.${m.messageRef}") != null) continue
            added = true
            val content = buildJsonObject {
                put("messageRef", m.messageRef)
                put("flagId", p.flagId)
                put("conversationRef", p.conversationRef)
                put("appSource", app)
                put("senderRef", m.senderRef)
                put(
                    "senderDisplayName",
                    m.senderDisplayName?.let { JsonPrimitive(it) } ?: kotlinx.serialization.json.JsonNull
                )
                put("text", m.text)
                put("sentAt", m.sentAt)
                put("sentAtMillis", parseIso(m.sentAt).toString())
                put("fromChild", m.fromChild)
            }
            val entry = sealAndAnnounce(ItemKind.MESSAGE, caseId, p.flagId, content)
            store.setMeta("msg.${m.messageRef}", entry.itemId)
        }
        // Captured messages carry their real sent times: re-check the case's patterns with them.
        if (added) caseChanged(caseId)
        p.screenshotRef?.let { ref ->
            // Media is never copied: only the fact, a hash of the reference and the time. Suspected
            // sexual images of minors are marked so nobody opens or forwards them by mistake.
            val sexual = setOf(HarmCategory.SEXTORTION, HarmCategory.GROOMING, HarmCategory.SEXUAL_HARASSMENT)
            val flagCategory = store.items().firstOrNull { it.kind == ItemKind.FLAG && it.flagId == p.flagId }
                ?.let {
                    CaseIndex.parse(it)["category"]?.let { c -> HarmCategory.fromWire((c as JsonPrimitive).content) }
                }
            sealAndAnnounce(
                ItemKind.MEDIA,
                caseId,
                p.flagId,
                buildJsonObject {
                    put("flagId", p.flagId)
                    put("conversationRef", p.conversationRef)
                    put("appSource", app)
                    put("mediaRefHash", Hashing.sha256Hex(ref.toByteArray()))
                    put("copied", false)
                    put("suspectedSexualImageOfMinor", flagCategory in sexual)
                    put("recordedAt", CaseIndex.iso(clock()))
                }
            )
        }
    }

    fun onPaired() = store.setMeta("account.child", "true")

    private val isChild get() = store.meta("account.child") == "true"

    // ------------------------------------------------------------------ reading

    fun cases(): List<CaseState> = CaseIndex.build(store.items(), labels(), emptyMap())

    fun case(caseId: String) = cases().firstOrNull { it.caseId == caseId }

    /** A machine translation made earlier for an export, if any. */
    fun translationOf(itemId: String) = translations.cached(itemId)

    fun timeline(caseId: String, actor: Actor = Actor.CHILD): List<TimelineEntry> {
        val c = case(caseId) ?: return emptyList()
        custody(actor, "view", caseId, emptyList())
        return CaseIndex.timeline(store.items(), caseId, c.offenderLabel)
    }

    fun verify(): ChainReport = vault.verify()

    // ------------------------------------------------------------------ export and share

    /** Builds the case file (JSON + PDF) for [recipient], timestamping the chain root first when online. */
    fun export(
        caseId: String,
        recipient: RecipientType,
        actor: Actor = Actor.CHILD,
        victimIdentity: String? = null,
        parentReport: ParentReport? = null
    ): CaseFile {
        val c = requireNotNull(case(caseId)) { "unknown case" }
        custody(actor, "export", caseId, emptyList(), recipient)
        val root = vault.root()
        val ts = runCatching { api?.timestamp(root) }.getOrNull()
        val items = store.items()
        val timeline = CaseIndex.timeline(items, caseId, c.offenderLabel)
        // Machine translations of non-English messages, beside the evidence (never inside it).
        val translated = translations.forCase(items.filter { it.caseId == caseId })
        return CaseFileBuilder.build(
            c, items, store.chain(), timeline, recipient, signer.publicKeyBase64(), signer.protection, ts,
            clock(),
            shareBaseUrl, victimIdentity, translated, parentReport
        )
    }

    /**
     * Starts a share. For a child (paired) account it waits for guardian.share.approved;
     * otherwise it goes ahead at once. Returns the request id.
     */
    fun requestShare(caseId: String, recipient: RecipientType, actor: Actor = Actor.CHILD): String {
        requireNotNull(case(caseId)) { "unknown case" }
        val requestId = UUID.randomUUID().toString()
        saveShare(ShareRecord(requestId, caseId, recipient, ShareStatus.PENDING_APPROVAL.wire, null))
        custody(actor, "share_requested", caseId, emptyList(), recipient, requestId)
        publisher.publish(
            Events.CASEFILE_SHARE_REQUESTED,
            CasefileShareRequestedPayload(requestId, caseId, recipient),
            caseId
        )
        // An adult's share needs nobody's approval. (Approvals only count for child accounts.)
        if (!isChild &&
            share(requestId)?.status == ShareStatus.PENDING_APPROVAL.wire
        ) {
            completeShare(requestId, Actor.ADULT_USER)
        }
        return requestId
    }

    fun onShareApproved(requestId: String) {
        val s = share(requestId) ?: return
        if (!isChild || s.status != ShareStatus.PENDING_APPROVAL.wire) return
        custody(Actor.PARENT, "share_approved", s.caseId, emptyList(), s.recipient, requestId)
        completeShare(requestId, Actor.PARENT)
    }

    fun onShareDeclined(requestId: String) {
        val s = share(requestId) ?: return
        if (s.status != ShareStatus.PENDING_APPROVAL.wire) return
        saveShare(s.copy(status = ShareStatus.DECLINED.wire))
        custody(Actor.PARENT, "share_declined", s.caseId, emptyList(), s.recipient, requestId)
    }

    /**
     * The parent reported a case (Guardian checked the family-key signature). No approval to wait
     * for: the parent is the approver. The link goes back to Guardian, which seals it for the parent.
     */
    fun onReportRequested(p: GuardianReportRequestedPayload) {
        if (share(p.requestId) != null) return
        if (case(p.caseId) == null) {
            publisher.publish(
                Events.CASEFILE_REPORT_READY,
                CasefileReportReadyPayload(p.requestId, p.caseId, ReportOutcome.FAILED),
                p.caseId
            )
            return
        }
        store.setMeta("report.${p.requestId}", if (p.anonymous) "anonymous" else "named")
        saveShare(ShareRecord(p.requestId, p.caseId, p.recipientType, ShareStatus.PENDING_APPROVAL.wire, null))
        custody(Actor.PARENT, "report_requested", p.caseId, emptyList(), p.recipientType, p.requestId)
        val record = completeShare(p.requestId, Actor.PARENT, ParentReport(p.anonymous, p.childNames))
        val uploaded = record.status != "built_offline" && record.link != null
        publisher.publish(
            Events.CASEFILE_REPORT_READY,
            CasefileReportReadyPayload(
                p.requestId,
                p.caseId,
                if (uploaded) ReportOutcome.READY else ReportOutcome.FAILED,
                if (uploaded) record.link else null
            ),
            p.caseId
        )
    }

    /** "anonymous", "named" or null (a share the child started). */
    fun reportKind(requestId: String): String? = store.meta("report.$requestId")

    private fun completeShare(requestId: String, actor: Actor, parentReport: ParentReport? = null): ShareRecord {
        val s = share(requestId)!!
        val file = export(s.caseId, s.recipient, actor, parentReport = parentReport)
        val sealed = ShareCrypto.encrypt(ShareCrypto.container(file))
        val link = "$shareBaseUrl/s/$requestId#k=${Base64Url.encode(sealed.key)}"
        var status = "built_offline"
        api?.let { a ->
            runCatching {
                val bundle = a.createBundle(
                    s.caseId,
                    BundleRequest(
                        Hashing.sha256Hex(sealed.blob),
                        (file.json["exhibits"] as kotlinx.serialization.json.JsonArray).size.toLong().coerceAtLeast(1),
                        sealed.blob.size.toLong()
                    )
                )
                a.upload(bundle.uploadUrl, sealed.blob)
                status = a.createShare(s.caseId, ShareRequest(requestId, s.recipient, bundle.bundleId)).status.wire
            }.onFailure { SafeLog.w("casefile.share.upload_failed") }
        }
        val done = s.copy(status = status, link = link)
        saveShare(done)
        custody(actor, "shared", s.caseId, emptyList(), s.recipient, requestId)
        SafeLog.i(
            "casefile.share.ready",
            LogField.id("request_id", requestId),
            LogField.bool(
                "uploaded",
                status != "built_offline"
            )
        )
        return done
    }

    fun share(requestId: String): ShareRecord? =
        store.meta("share.$requestId")?.split('\u0001')?.let { (caseId, r, status, link) ->
            ShareRecord(requestId, caseId, RecipientType.fromWire(r), status, link.ifEmpty { null })
        }

    fun shares(caseId: String): List<ShareRecord> = store.meta("shares.$caseId")?.split(',')?.mapNotNull {
        share(it)
    }.orEmpty()

    private fun saveShare(s: ShareRecord) {
        store.setMeta(
            "share.${s.requestId}",
            listOf(s.caseId, s.recipient.wire, s.status, s.link.orEmpty()).joinToString("\u0001")
        )
        val ids = store.meta("shares.${s.caseId}")?.split(',').orEmpty()
        if (s.requestId !in ids) store.setMeta("shares.${s.caseId}", (ids + s.requestId).joinToString(","))
    }

    // ------------------------------------------------------------------ retention

    /**
     * Permanently deletes the vault. A child's vault needs the parent's approval first.
     * There is no deletion-approval event in the contracts yet, so the shell passes
     * [parentApproved] once Guardian provides one.
     */
    fun deleteVault(parentApproved: Boolean = false): DeletionResult {
        if (isChild && !parentApproved) return DeletionResult.NEEDS_PARENT_APPROVAL
        store.wipe()
        SafeLog.i("casefile.vault.deleted")
        return DeletionResult.DELETED
    }

    // ------------------------------------------------------------------ internals

    private fun sealAndAnnounce(kind: ItemKind, caseId: String, flagId: String, content: JsonObject): ChainEntry {
        val entry = vault.seal(UUID.randomUUID().toString(), kind, caseId, flagId, content)
        publisher.publish(
            Events.CASEFILE_EVIDENCE_SEALED,
            CasefileEvidenceSealedPayload(entry.itemId, flagId, entry.itemHash, entry.index),
            caseId
        )
        return entry
    }

    private fun custody(
        actor: Actor,
        action: String,
        caseId: String,
        itemIds: List<String>,
        recipient: RecipientType? = null,
        requestId: String? = null
    ) {
        vault.seal(
            UUID.randomUUID().toString(),
            ItemKind.CUSTODY,
            null,
            null,
            buildJsonObject {
                put("action", action)
                put("actor", actor.name.lowercase())
                put("caseId", caseId)
                put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it)) } })
                recipient?.let { put("recipientType", it.wire) }
                requestId?.let { put("requestId", it) }
                put("at", CaseIndex.iso(clock()))
            }
        )
    }

    private fun caseChanged(caseId: String) {
        val c = case(caseId) ?: return
        publisher.publish(
            Events.CASEFILE_CASE_UPDATED,
            CasefileCaseUpdatedPayload(caseId, c.offenderLabel, c.incidentCount, c.topSeverity),
            caseId
        )
        for (reason in Patterns.reasons(c)) {
            val key = "case.$caseId.escalated.${reason.wire}"
            if (store.meta(key) != null) continue
            store.setMeta(key, CaseIndex.iso(clock()))
            publisher.publish(
                Events.CASEFILE_CASE_ESCALATED,
                CasefileCaseEscalatedPayload(
                    caseId,
                    c.offenderLabel,
                    reason,
                    if (reason ==
                        EscalationReason.GROOMING_DETECTED
                    ) {
                        c.groomingStages.lastOrNull()
                    } else {
                        null
                    }
                ),
                caseId
            )
            SafeLog.i("casefile.case.escalated", LogField.enum("reason", reason))
        }
    }

    /** One case per offender: a new sender gets a new pseudonymous label and case. */
    private fun caseFor(senderRef: String): String {
        store.meta("profile.$senderRef")?.let { return it }
        val caseId = UUID.randomUUID().toString()
        val n = store.meta("labels.next")?.toInt() ?: 0
        store.setMeta("labels.next", (n + 1).toString())
        store.setMeta("case.$caseId.label", Patterns.label(n))
        store.setMeta("profile.$senderRef", caseId)
        store.setMeta(
            "cases",
            (
                store.meta("cases")?.split(',').orEmpty().filter {
                    it.isNotEmpty()
                } + caseId
                ).joinToString(",")
        )
        return caseId
    }

    /**
     * Link confirmation: the adult victim (or the parent, for a child) confirms that another
     * sender ref is the same offender. Later messages from it join this case.
     * TODO(casefile): fetch suggestions from POST /v1/ai/link-suggestions (MVP+1).
     */
    fun confirmLink(caseId: String, senderRef: String, actor: Actor) {
        store.setMeta("profile.$senderRef", caseId)
        custody(actor, "link_confirmed", caseId, emptyList())
    }

    private fun labels(): Map<String, String> =
        store.meta("cases")?.split(',').orEmpty().filter { it.isNotEmpty() }.associateWith {
            store.meta("case.$it.label")
                ?: "Person A"
        }

    private fun appOf(flagId: String): String =
        store.items().firstOrNull { it.kind == ItemKind.FLAG && it.flagId == flagId }
            ?.let { (CaseIndex.parse(it)["appSource"] as? JsonPrimitive)?.content } ?: "unknown"

    private fun parseIso(s: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(s.replace(Regex("\\.\\d+Z$"), "Z"))!!.time
    }.getOrDefault(clock())
}
