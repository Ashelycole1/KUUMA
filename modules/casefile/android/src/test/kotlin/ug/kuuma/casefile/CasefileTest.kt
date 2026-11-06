package ug.kuuma.casefile

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.ai.runtime.DummyHarmClassifier
import ug.kuuma.casefile.export.ParentReport
import ug.kuuma.casefile.export.RootTimestamp
import ug.kuuma.casefile.export.ShareCrypto
import ug.kuuma.casefile.export.Translator
import ug.kuuma.casefile.vault.ChainProblem
import ug.kuuma.casefile.vault.InMemoryVaultStore
import ug.kuuma.casefile.vault.ItemKind
import ug.kuuma.casefile.vault.ItemSigner
import ug.kuuma.casefile.vault.Vault
import ug.kuuma.contracts.BundleRequest
import ug.kuuma.contracts.BundleResponse
import ug.kuuma.contracts.ContractJson
import ug.kuuma.contracts.Envelope
import ug.kuuma.contracts.EscalationReason
import ug.kuuma.contracts.EventPayload
import ug.kuuma.contracts.EventType
import ug.kuuma.contracts.Events
import ug.kuuma.contracts.GroomingStage
import ug.kuuma.contracts.GuardianReportRequestedPayload
import ug.kuuma.contracts.Namespace
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.ReportOutcome
import ug.kuuma.contracts.ShareRequest
import ug.kuuma.contracts.ShareResponse
import ug.kuuma.contracts.ShareStatus
import ug.kuuma.contracts.TranslateRequest
import ug.kuuma.contracts.TranslateResponse
import ug.kuuma.contracts.testing.ContractAssert
import ug.kuuma.core.bus.EventBus
import ug.kuuma.core.crypto.Base64Url
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleContext
import ug.kuuma.core.module.ModuleRegistry
import ug.kuuma.fakes.FakeSet
import ug.kuuma.fakes.ScenarioLibrary

/** Software EC P-256 signer standing in for the Keystore in JVM tests. */
class TestSigner : ItemSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()
    override val protection = "SOFTWARE"
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private)
        update(data)
        sign()
    }
    override fun verify(data: ByteArray, signature: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
        initVerify(pair.public)
        update(data)
        verify(signature)
    }
    override fun publicKeyBase64(): String = ug.kuuma.casefile.vault.Base64.encode(pair.public.encoded)
}

class VaultTest {
    private val store = InMemoryVaultStore()
    private val vault = Vault(store, TestSigner())

    private fun fill(n: Int) = (0 until n).map { i ->
        vault.seal(
            "item-$i",
            ItemKind.MESSAGE,
            "case",
            "flag",
            buildJsonObject {
                put("text", "message $i")
                put("n", i)
            }
        )
    }

    @Test fun `an untouched vault verifies`() {
        fill(5)
        val r = vault.verify()
        assertTrue(r.problems.toString(), r.intact)
        assertEquals(5L, r.length)
        assertEquals(store.head()!!.chainHash, r.root)
    }

    @Test fun `canonical JSON ignores key order`() {
        val a = Json.parseToJsonElement("""{"b":1,"a":{"y":[1,2],"x":"é"}}""")
        val b = Json.parseToJsonElement("""{ "a" : { "x":"é", "y":[1, 2] }, "b":1 }""")
        assertEquals(Vault.canonical(a), Vault.canonical(b))
    }

    @Test fun `editing an item is caught`() {
        fill(3)
        store.tamperContent("item-1", """{"n":1,"text":"something else"}""")
        assertEquals(listOf(ChainProblem(1, ChainProblem.Kind.ITEM_ALTERED)), vault.verify().problems)
    }

    @Test fun `deleting an item is caught`() {
        fill(3)
        store.tamperDeleteItem("item-2")
        assertTrue(ChainProblem(2, ChainProblem.Kind.ITEM_MISSING) in vault.verify().problems)
    }

    @Test fun `deleting a link from the middle is caught`() {
        fill(4)
        store.tamperDeleteLink(1)
        val kinds = vault.verify().problems.map { it.kind }.toSet()
        assertTrue(
            kinds.toString(),
            ChainProblem.Kind.CHAIN_BROKEN in kinds && ChainProblem.Kind.ITEM_NOT_CHAINED in kinds
        )
    }

    @Test fun `truncating the chain is caught`() {
        fill(4)
        store.tamperDeleteLink(3)
        store.tamperDeleteItem("item-3")
        assertFalse(vault.verify().intact)
    }

    @Test fun `links signed by another key are caught`() {
        fill(2)
        // An attacker who rewrites items and re-hashes the chain still cannot sign as this phone.
        val withOtherKey = Vault(store, TestSigner())
        assertTrue(withOtherKey.verify().problems.all { it.kind == ChainProblem.Kind.BAD_SIGNATURE })
        assertEquals(2, withOtherKey.verify().problems.size)
    }
}

class CasefileEngineTest {
    private class Host(override val namespace: Namespace) : KuumaModule {
        override val displayName = namespace.wire
        lateinit var ctx: ModuleContext
        override fun onRegister(context: ModuleContext) {
            ctx = context
        }
    }

    private class Api : CasesApi {
        val bundles = mutableListOf<BundleRequest>()
        val uploads = mutableListOf<ByteArray>()
        val shares = mutableListOf<ShareRequest>()
        val timestamped = mutableListOf<String>()
        override fun timestamp(hash: String): RootTimestamp {
            timestamped += hash
            return RootTimestamp(hash, "2026-11-05T09:30:00Z", "c2ln", "kuuma-local-mock-tsa")
        }
        override fun createBundle(caseId: String, request: BundleRequest): BundleResponse {
            bundles += request
            return BundleResponse("d4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f7a", "http://minio/upload", "2026-11-05T10:30:00Z")
        }
        override fun upload(url: String, blob: ByteArray) {
            uploads += blob
        }
        override fun createShare(caseId: String, request: ShareRequest): ShareResponse {
            shares += request
            return ShareResponse(request.requestId, caseId, ShareStatus.COMPLETED, "2026-12-05T09:30:00Z")
        }
    }

    /** Stands in for /v1/ai/translate: records what left the phone, can go offline. */
    private class FakeTranslator : Translator {
        val sent = mutableListOf<TranslateRequest>()
        var online = true
        override fun translate(request: TranslateRequest): TranslateResponse {
            if (!online) throw java.io.IOException("offline")
            sent += request
            return TranslateResponse(
                "EN(${request.text})",
                request.from,
                request.to,
                true,
                "sunflower-14b:q4",
                "translate@1",
                1200
            )
        }
    }

    private class Captured(val type: EventType<*>, val env: Envelope<*>)

    private val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
    private val events = mutableListOf<Captured>()
    private val store = InMemoryVaultStore()
    private val api = Api()
    private val translator = FakeTranslator()
    private val fakes = FakeSet(DummyHarmClassifier(), OpaqueRefs(ByteArray(32) { 5 }))
    private lateinit var engine: CasefileEngine

    init {
        val registry = ModuleRegistry(bus)
        fakes.all.forEach(registry::register)
        fakes.casefile.enabled = false // the real vault replaces Casefile's fake
        val host = Host(Namespace.CASEFILE)
        registry.register(host)
        engine = CasefileEngine(host.ctx.publisher, store, TestSigner(), api, translator = translator)
        bus.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { engine.onFlagged(it.payload) }
        bus.subscribe(Events.SENTINEL_FLAG_UPDATED) { engine.onFlagUpdated(it.payload) }
        bus.subscribe(Events.SENTINEL_CAPTURE_READY) { engine.onCapture(it.payload) }
        bus.subscribe(Events.GUARDIAN_SHARE_APPROVED) { engine.onShareApproved(it.payload.requestId) }
        bus.subscribeAll { t, e -> events += Captured(t, e) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun json(c: Captured) =
        ContractJson.encode(c.type as EventType<EventPayload>, c.env as Envelope<EventPayload>, ContractJson.strict)

    private inline fun <reified P : EventPayload> payloads(type: EventType<P>) = events.filter {
        it.type === type
    }.map { it.env.payload as P }

    private fun play(name: String) = fakes.player.play(ScenarioLibrary.load(name))

    @Test fun `luganda insults become one sealed case for Person A, escalated once per reason`() {
        play("luganda-insults")
        val case = engine.cases().single()
        assertEquals("Person A", case.offenderLabel)
        assertEquals(4L, case.incidentCount)
        assertEquals(4, case.messageCount) // each captured message sealed once
        assertTrue(engine.verify().intact)
        val reasons = payloads(Events.CASEFILE_CASE_ESCALATED).map { it.reason }
        assertEquals(listOf(EscalationReason.SEVERITY_THRESHOLD, EscalationReason.REPEATED_INCIDENTS), reasons)
        assertEquals(8, payloads(Events.CASEFILE_EVIDENCE_SEALED).size)
        events.forEach { ContractAssert.validateEventJson(json(it)) }
        ContractAssert.assertPublishes(
            Namespace.CASEFILE,
            Events.CASEFILE_CASE_UPDATED,
            events.last {
                it.type ===
                    Events.CASEFILE_CASE_UPDATED
            }.env as Envelope<ug.kuuma.contracts.CasefileCaseUpdatedPayload>
        )
    }

    @Test fun `non-English messages get labelled machine translations beside the evidence`() {
        play("luganda-insults")
        val case = engine.cases().single()
        val file = engine.export(case.caseId, RecipientType.POLICE)
        val translations = file.json["translations"]!!.jsonArray
        assertEquals(translator.sent.size, translations.size)
        assertTrue(translations.isNotEmpty())
        val t = translations.first().jsonObject
        assertEquals("lg", t["from"]!!.jsonPrimitive.content)
        assertEquals("true", t["machineTranslated"]!!.jsonPrimitive.content)
        assertEquals("translate@1", t["promptVersion"]!!.jsonPrimitive.content)
        // Names left the phone as placeholders and were put back on the phone.
        assertTrue(translator.sent.none { "invented" in it.text })
        val pdf = String(file.pdf, Charsets.ISO_8859_1)
        assertTrue(pdf.contains("Machine translation from Luganda"))
        assertTrue(pdf.contains("sunflower-14b:q4, translate@1"))
        // The exhibits are untouched: the sealed originals are still the evidence.
        assertTrue(engine.verify().intact)
        assertTrue(file.json["exhibits"]!!.jsonArray.none { "EN(" in it.toString() })
        // A second export reuses the translations, even offline.
        val before = translator.sent.size
        translator.online = false
        val again = engine.export(case.caseId, RecipientType.SCHOOL)
        assertEquals(before, translator.sent.size)
        assertEquals(translations.size, again.json["translations"]!!.jsonArray.size)
    }

    @Test fun `case files name the sender as the phone showed them, from the offender's messages only`() {
        play("luganda-insults")
        val file = engine.export(engine.cases().single().caseId, RecipientType.POLICE)
        val details = file.json["senderDetails"]!!.jsonObject
        val names = details["displayNames"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Classmate (invented)"), names) // not the child, not the friend
        val pdf = String(file.pdf, Charsets.ISO_8859_1)
        assertTrue(pdf.contains("Sender details"))
        assertTrue(pdf.contains("Classmate"))
        // Names and numbers never go to the translator.
        assertTrue(translator.sent.none { "Classmate" in it.text })
    }

    @Test fun `an anonymous parent report leaves out the child and still verifies`() {
        play("luganda-insults")
        val case = engine.cases().single()
        val report = ParentReport(anonymous = true, childNames = listOf("Ggwe"))
        val file = engine.export(case.caseId, RecipientType.POLICE, Actor.PARENT, parentReport = report)
        val json = file.json
        assertEquals("null", json["victim"].toString())
        assertEquals("true", json["report"]!!.jsonObject["anonymous"]!!.jsonPrimitive.content)
        val exhibits = json["exhibits"]!!.jsonArray.map { it.jsonObject }
        val messages = exhibits.filter { it["kind"]!!.jsonPrimitive.content == "MESSAGE" }
        // Only the offender's messages: no child replies, no friends.
        val contents = messages.mapNotNull { it["content"]?.jsonPrimitive?.content }
        assertTrue(contents.none { "\"fromChild\":true" in it })
        assertTrue(contents.none { "Friend (invented)" in it })
        // The message naming the child is withheld: hash only.
        val withheld = messages.filter { it["withheld"]?.jsonPrimitive?.content == "true" }
        assertEquals(1, withheld.size)
        assertTrue(withheld.single()["content"] == null)
        assertTrue(json.toString().contains("itemHash"))
        assertFalse(json.toString().contains("Ggwe kasiru"))
        val pdf = String(file.pdf, Charsets.ISO_8859_1)
        assertTrue(pdf.contains("message withheld"))
        assertFalse(pdf.contains("Ggwe"))
        assertFalse(pdf.contains("Leka kunvuma")) // the child's own reply
        // Every exhibit still sits in the signed chain.
        val chain = store.chain().associateBy { it.index }
        exhibits.forEach { x ->
            assertEquals(
                chain.getValue(x["chainIndex"]!!.jsonPrimitive.content.toLong()).itemHash,
                x["itemHash"]!!.jsonPrimitive.content
            )
        }
    }

    @Test fun `a parent's report is built at once and its link handed back on the phone`() {
        play("luganda-insults")
        val case = engine.cases().single()
        events.clear()
        val id = "abababab-abab-4bab-8bab-abababababab"
        engine.onReportRequested(
            GuardianReportRequestedPayload(id, case.caseId, RecipientType.SCHOOL, true, listOf("Ggwe"))
        )
        val ready = payloads(Events.CASEFILE_REPORT_READY).single()
        assertEquals(ReportOutcome.READY, ready.status)
        assertTrue(ready.link!!.contains("#k="))
        assertTrue(payloads(Events.CASEFILE_SHARE_REQUESTED).isEmpty()) // nothing to approve: the parent asked
        assertEquals("anonymous", engine.reportKind(id))
        assertEquals(1, api.shares.size)
        // Asked again (duplicate delivery): built once.
        engine.onReportRequested(
            GuardianReportRequestedPayload(id, case.caseId, RecipientType.SCHOOL, true, emptyList())
        )
        assertEquals(1, api.shares.size)
    }

    @Test fun `offline export works without translations, English is never sent`() {
        translator.online = false
        play("luganda-insults")
        val luganda = engine.export(engine.cases().single().caseId, RecipientType.POLICE)
        assertEquals(0, luganda.json["translations"]!!.jsonArray.size)
        translator.online = true
        play("english-threat")
        engine.cases().forEach { engine.export(it.caseId, RecipientType.POLICE) }
        assertTrue(translator.sent.isNotEmpty())
        assertTrue(translator.sent.all { it.from.wire == "lg" })
    }

    @Test fun `grooming is tracked by stage`() {
        play("slow-grooming")
        val case = engine.cases().single()
        assertEquals(GroomingStage.entries.drop(1), case.groomingStages) // trust_building is never flagged
        // Late-night contact is counted from when messages were sent (22:30-23:10), not when Kuuma saw them.
        assertEquals(4, case.lateNightCount(java.util.TimeZone.getTimeZone("UTC")))
        val timeline = engine.timeline(case.caseId)
        assertEquals(ItemKind.MESSAGE, timeline.first().kind) // each flag sits after its message
        assertEquals(ItemKind.FLAG, timeline[1].kind)
        val grooming = payloads(Events.CASEFILE_CASE_ESCALATED).single {
            it.reason == EscalationReason.GROOMING_DETECTED
        }
        assertEquals(GroomingStage.GIFTS_FLATTERY, grooming.groomingStage)
    }

    @Test fun `exported case files re-verify from their own contents`() {
        play("english-threat")
        val case = engine.cases().single()
        val file = engine.export(case.caseId, RecipientType.POLICE)
        assertTrue(String(file.pdf, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertTrue(String(file.pdf, Charsets.ISO_8859_1).contains("Person A"))
        assertEquals(api.timestamped.single(), file.root)

        // What a recipient does: re-hash every exhibit and walk the chain.
        val links = file.json["chain"]!!.jsonObject["links"]!!.jsonArray.map { it.jsonObject }
        val byIndex = links.associateBy { it["index"]!!.jsonPrimitive.content.toLong() }
        var prev = Vault.GENESIS
        links.forEach { l ->
            assertEquals(prev, l["prevHash"]!!.jsonPrimitive.content)
            assertEquals(
                l["chainHash"]!!.jsonPrimitive.content,
                Vault.chainHash(
                    prev,
                    l["itemHash"]!!.jsonPrimitive.content,
                    l["index"]!!.jsonPrimitive.content.toLong()
                )
            )
            prev = l["chainHash"]!!.jsonPrimitive.content
        }
        assertEquals(file.root, prev)
        for (x in file.json["exhibits"]!!.jsonArray.map { it.jsonObject }) {
            val kind = ItemKind.valueOf(x["kind"]!!.jsonPrimitive.content)
            val hash = Vault.itemHash(kind, x["content"]!!.jsonPrimitive.content)
            assertEquals(
                byIndex.getValue(x["chainIndex"]!!.jsonPrimitive.content.toLong())["itemHash"]!!.jsonPrimitive.content,
                hash
            )
        }
        // The export itself is in the custody log, sealed into the chain.
        assertTrue(store.items().any { it.kind == ItemKind.CUSTODY && it.contentJson.contains("\"export\"") })
        assertEquals(null, file.json["victim"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else it })
    }

    @Test fun `adult share is an encrypted upload and the cloud sees only hashes and ciphertext`() {
        play("english-threat")
        val caseId = engine.cases().single().caseId
        val requestId = engine.requestShare(caseId, RecipientType.SCHOOL)
        val share = engine.share(requestId)!!
        assertEquals("completed", share.status)
        val blob = api.uploads.single()
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("hurt"))
        assertEquals(Hashing.sha256Hex(blob), api.bundles.single().bundleHash)
        assertEquals(api.bundles.single().let { "d4e5f6a7-b8c9-4d0e-8f1a-2b3c4d5e6f7a" }, api.shares.single().bundleId)
        // The key is only in the link's fragment; the recipient can decrypt with it.
        val key = Base64Url.decode(share.link!!.substringAfter("#k="))
        val container = Json.parseToJsonElement(String(ShareCrypto.decrypt(blob, key))).jsonObject
        assertEquals("kuuma-share/1", container["format"]!!.jsonPrimitive.content)
        assertTrue((container["case"] as JsonObject)["exhibits"] is JsonArray)
        assertTrue(payloads(Events.CASEFILE_SHARE_REQUESTED).single().requestId == requestId)
    }

    @Test fun `child share waits for the parent, deletion too`() {
        engine.onPaired()
        play("english-threat")
        val caseId = engine.cases().single().caseId
        // Turn off the auto-approving fake parent: nothing is shared without approval.
        fakes.guardian.enabled = false
        val requestId = engine.requestShare(caseId, RecipientType.POLICE)
        assertEquals("pending_approval", engine.share(requestId)!!.status)
        assertTrue(api.uploads.isEmpty())
        fakes.guardian.enabled = true
        val second = engine.requestShare(caseId, RecipientType.POLICE) // the fake parent approves at once
        assertEquals("completed", engine.share(second)!!.status)
        assertEquals(DeletionResult.NEEDS_PARENT_APPROVAL, engine.deleteVault())
        assertEquals(DeletionResult.DELETED, engine.deleteVault(parentApproved = true))
        assertTrue(engine.cases().isEmpty())
    }

    @Test fun `vault content never leaves in an event`() {
        play("luganda-insults")
        for (c in events.filter { it.type.publisher == Namespace.CASEFILE }) {
            val text = json(c)
            assertFalse(text, text.contains("musiru") || text.contains("Classmate"))
        }
    }
}

/**
 * Writes a real exported case file and an encrypted share to build/casefile-fixture/, from an
 * invented scenario. modules/casefile/web/test/fixtures holds a copy, so the TypeScript
 * verifier is tested against exactly what the vault produces. Regenerate after format changes:
 *   ./gradlew :casefile:testDebugUnitTest && then copy build/casefile-fixture into modules/casefile/web/test/fixtures
 */
class FixtureWriter {
    @Test fun `write cross-language fixture`() {
        val bus = EventBus({ "7a9e2c41-5d3b-4f6a-8e1c-2b3d4e5f6a7b" })
        val registry = ModuleRegistry(bus)
        val fakes = FakeSet(DummyHarmClassifier(), OpaqueRefs(ByteArray(32) { 5 }))
        fakes.all.forEach(registry::register)
        fakes.casefile.enabled = false
        val host = object : KuumaModule {
            override val namespace = Namespace.CASEFILE
            override val displayName = "fixture"
            lateinit var ctx: ModuleContext
            override fun onRegister(context: ModuleContext) {
                ctx = context
            }
        }
        registry.register(host)
        val engine = CasefileEngine(host.ctx.publisher, InMemoryVaultStore(), TestSigner())
        bus.subscribe(Events.SENTINEL_MESSAGE_FLAGGED) { engine.onFlagged(it.payload) }
        bus.subscribe(Events.SENTINEL_CAPTURE_READY) { engine.onCapture(it.payload) }
        fakes.player.play(ScenarioLibrary.load("english-threat"))
        val case = engine.cases().single()
        val file = engine.export(case.caseId, RecipientType.POLICE)
        val sealed = ShareCrypto.encrypt(ShareCrypto.container(file))
        val dir = java.io.File("build/casefile-fixture").apply { mkdirs() }
        java.io.File(dir, "case-file.json").writeText(file.json.toString())
        java.io.File(dir, "share.bin").writeBytes(sealed.blob)
        java.io.File(dir, "share.key").writeText(Base64Url.encode(sealed.key))
    }
}
