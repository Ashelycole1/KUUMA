package ug.kuuma.sentinel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.AnalyseRequest
import ug.kuuma.contracts.AnalyseResponse
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.ArtifactManifest
import ug.kuuma.contracts.FeedbackRequest
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Severity
import ug.kuuma.contracts.Verdict
import ug.kuuma.core.crypto.Hashing
import ug.kuuma.sentinel.capture.ScreenNode
import ug.kuuma.sentinel.capture.ScreenParser
import ug.kuuma.sentinel.capture.ScreenSnapshot
import ug.kuuma.sentinel.cloud.AiGateway
import ug.kuuma.sentinel.cloud.BudgetPolicy
import ug.kuuma.sentinel.cloud.CloudJob
import ug.kuuma.sentinel.cloud.CloudQueue
import ug.kuuma.sentinel.cloud.DeviceState
import ug.kuuma.sentinel.ingest.Deduplicator
import ug.kuuma.sentinel.ingest.NotificationParser
import ug.kuuma.sentinel.ingest.NotificationSnapshot
import ug.kuuma.sentinel.ingest.StyleMessage
import ug.kuuma.sentinel.normalize.LanguageGuesser
import ug.kuuma.sentinel.normalize.LexiconBundle
import ug.kuuma.sentinel.normalize.LexiconEntry
import ug.kuuma.sentinel.normalize.LexiconFile
import ug.kuuma.sentinel.normalize.LexiconUpdater
import ug.kuuma.sentinel.normalize.Normaliser
import ug.kuuma.sentinel.screen.ContactStats
import ug.kuuma.sentinel.screen.Route
import ug.kuuma.sentinel.screen.Screening
import ug.kuuma.sentinel.screen.SentinelPolicy

class NotificationParserTest {
    private fun n(
        title: String?,
        text: String?,
        vararg msgs: StyleMessage,
        conv: String? = null,
        group: Boolean? = null,
        summary: Boolean = false
    ) = NotificationSnapshot("k", "com.whatsapp", 1_000, title, text, conv, group, summary, msgs.toList())

    @Test fun `one-to-one WhatsApp`() {
        val m = NotificationParser.parse(n("Kato", "Oli musiru"), AppSource.WHATSAPP).single()
        assertEquals("Kato", m.conversationKey)
        assertEquals("Kato", m.senderKey)
        assertFalse(m.isGroup)
    }

    @Test fun `legacy WhatsApp group formats`() {
        val a = NotificationParser.parse(n("P7 Class: Kato", "hello"), AppSource.WHATSAPP).single()
        assertEquals("P7 Class" to "Kato", a.conversationKey to a.senderKey)
        assertTrue(a.isGroup)
        val b = NotificationParser.parse(n("Kato @ P7 Class", "hello"), AppSource.WHATSAPP).single()
        assertEquals("P7 Class" to "Kato", b.conversationKey to b.senderKey)
    }

    @Test fun `MessagingStyle group, own messages and count suffix`() {
        val ms = NotificationParser.parse(
            n(
                "P7 Class (3 messages)",
                null,
                StyleMessage("hi", "Kato", 1),
                StyleMessage("ok", null, 2),
                StyleMessage("bye", "Amina", 3),
                conv = "P7 Class (3 messages)"
            ),
            AppSource.WHATSAPP
        )
        assertEquals(3, ms.size)
        assertTrue(ms.all { it.isGroup && it.conversationKey == "P7 Class" })
        assertTrue(ms[1].fromChild)
        assertEquals(NotificationParser.SELF, ms[1].senderKey)
    }

    @Test fun `summaries and system notices are ignored`() {
        assertTrue(NotificationParser.parse(n("WhatsApp", "5 new messages from 2 chats"), AppSource.WHATSAPP).isEmpty())
        assertTrue(NotificationParser.parse(n("Kato", "Missed voice call"), AppSource.WHATSAPP).isEmpty())
        assertTrue(NotificationParser.parse(n("Kato", "hi", summary = true), AppSource.WHATSAPP).isEmpty())
    }

    @Test fun `re-posted messages are deduplicated`() {
        val d = Deduplicator()
        val m = NotificationParser.parse(n("Kato", "hi"), AppSource.WHATSAPP).single()
        assertTrue(d.isNew(m))
        assertFalse(d.isNew(m.copy(sentAt = m.sentAt + 30_000)))
        assertTrue(d.isNew(m.copy(sentAt = m.sentAt + 10 * 60_000)))
    }
}

class NormaliserTest {
    @Test fun `leetspeak stretching shorthand and emoji`() {
        val n = Normaliser()
        assertEquals("you are so stupid knife", n.normalise("U r sooo STUP1D 🔪"))
        assertEquals("oli musiru nnyo", n.normalise("Oli musiiiru nnyo")) // doubles like "nnyo" survive
    }

    @Test fun `lexicon variants map to their term`() {
        val lex =
            LexiconBundle(
                "1.0.0",
                listOf(
                    LexiconFile(
                        "lg",
                        "1.0.0",
                        listOf(LexiconEntry("musiru", listOf("msiru", "muziru"), "insult", "medium"))
                    )
                )
            )
        assertEquals("oli musiru", Normaliser(lex).normalise("oli msiru"))
    }

    @Test fun `language guess`() {
        assertEquals(Language.LG, LanguageGuesser.guess("oli musiru nnyo"))
        assertEquals(Language.SW, LanguageGuesser.guess("wewe ni mjinga sana"))
        assertEquals(Language.EN, LanguageGuesser.guess("i will hurt you tomorrow"))
        assertEquals(Language.EN, LanguageGuesser.guess("😀"))
    }
}

class ScreeningTest {
    private val policy = SentinelPolicy()

    @Test fun routes() {
        val thr = Screening.threshold(policy, null)
        assertEquals(
            Route.FLAG_AND_CONFIRM,
            Screening.route(Verdict(true, HarmCategory.THREAT, Severity.HIGH, 0.9), thr)
        )
        assertEquals(Route.FLAG, Screening.route(Verdict(true, HarmCategory.INSULT, Severity.MEDIUM, 0.9), thr))
        assertEquals(Route.ASK_CLOUD, Screening.route(Verdict(true, HarmCategory.INSULT, Severity.MEDIUM, 0.5), thr))
        assertEquals(Route.ASK_CLOUD, Screening.route(Verdict(false, confidence = 0.4), thr))
        assertEquals(Route.CLEAR, Screening.route(Verdict(false, confidence = 0.95), thr))
        assertEquals(Route.CLEAR, Screening.route(Verdict(true, HarmCategory.INSULT, Severity.LOW, 0.1), thr))
    }

    @Test fun `dismissed flags raise a sender's threshold`() {
        val stats = ContactStats("s", firstSeen = 0, lastSeen = 0, dismissed = 2)
        assertEquals(0.8, Screening.threshold(policy, stats), 1e-9)
        assertEquals(0.9, Screening.threshold(policy, stats.copy(dismissed = 10)), 1e-9)
    }
}

class ScreenParserTest {
    private fun row(text: String, outgoing: Boolean = false, sender: String? = null) = ScreenNode(
        viewId = "com.whatsapp:id/main_layout",
        children = listOfNotNull(
            sender?.let { ScreenNode("com.whatsapp:id/name_in_group_tv", it) },
            ScreenNode("com.whatsapp:id/message_text", text),
            if (outgoing) ScreenNode("com.whatsapp:id/status", contentDescription = "Read") else null
        )
    )

    @Test fun `reads a WhatsApp chat`() {
        val root =
            ScreenNode(
                children = listOf(
                    ScreenNode("com.whatsapp:id/conversation_contact_name", "Kato"),
                    row("Oli musiru"),
                    row("Leka", outgoing = true)
                )
            )
        val chat = ScreenParser.parse(ScreenSnapshot("com.whatsapp", root, 0))!!
        assertEquals("Kato", chat.title)
        assertEquals(listOf("Oli musiru" to false, "Leka" to true), chat.messages.map { it.text to it.fromChild })
        assertEquals("Kato", chat.messages[0].sender)
    }

    @Test fun `group sender names and non-chat screens`() {
        val root =
            ScreenNode(
                children = listOf(
                    ScreenNode("com.whatsapp:id/conversation_contact_name", "P7 Class"),
                    row("hi", sender = "Amina")
                )
            )
        assertEquals("Amina", ScreenParser.parse(ScreenSnapshot("com.whatsapp", root, 0))!!.messages.single().sender)
        assertEquals(
            null,
            ScreenParser.parse(
                ScreenSnapshot("com.whatsapp", ScreenNode(children = listOf(ScreenNode(text = "Chats"))), 0)
            )
        )
        assertEquals(null, ScreenParser.parse(ScreenSnapshot("com.example", root, 0)))
    }
}

class CloudQueueTest {
    private class Gateway(var fail: Boolean = false) : AiGateway {
        val analysed = mutableListOf<AnalyseRequest>()
        val feedback = mutableListOf<FeedbackRequest>()
        override fun analyse(request: AnalyseRequest): AnalyseResponse {
            if (fail) throw java.io.IOException("down")
            analysed += request
            return AnalyseResponse(Verdict(false, confidence = 0.9), emptyList(), "stub")
        }
        override fun feedback(request: FeedbackRequest) {
            feedback += request
        }
        override fun latestLexicon() = error("unused")
        override fun download(url: String) = error("unused")
    }

    private fun job(i: Int, at: Long = 0) = CloudJob.Analyse("t$i", AnalyseRequest("text $i"), at)

    @Test fun `sends on wifi, batches on mobile data, holds on low battery`() {
        val g = Gateway()
        val q = CloudQueue(g, { _, _ -> })
        repeat(3) { q.enqueue(job(it, at = 0)) }
        assertEquals(CloudQueue.HOLD_RECHECK, q.pump(1, DeviceState(batteryPercent = 10, charging = false)))
        assertEquals(0, g.analysed.size)
        // Mobile data: wait for a full batch or the interval.
        assertEquals(BudgetPolicy.BATCH_INTERVAL_MILLIS - 1, q.pump(1, DeviceState(unmetered = false)))
        assertEquals(0, g.analysed.size)
        q.pump(BudgetPolicy.BATCH_INTERVAL_MILLIS, DeviceState(unmetered = false))
        assertEquals(3, g.analysed.size)
        repeat(5) { q.enqueue(job(10 + it, at = BudgetPolicy.BATCH_INTERVAL_MILLIS)) }
        q.pump(BudgetPolicy.BATCH_INTERVAL_MILLIS + 1, DeviceState(unmetered = false)) // full batch: no wait
        assertEquals(8, g.analysed.size)
        q.enqueue(job(20, at = BudgetPolicy.BATCH_INTERVAL_MILLIS + 2))
        q.pump(BudgetPolicy.BATCH_INTERVAL_MILLIS + 3, DeviceState()) // Wi-Fi: straight away
        assertEquals(9, g.analysed.size)
    }

    @Test fun `a message still waiting for its first verdict is sent at once, even on mobile data`() {
        val g = Gateway()
        val q = CloudQueue(g, { _, _ -> })
        q.enqueue(CloudJob.Analyse("u", AnalyseRequest("oli otya"), 0, urgent = true))
        q.pump(1, DeviceState(unmetered = false))
        assertEquals(1, g.analysed.size)
        // Low battery still holds everything.
        q.enqueue(CloudJob.Analyse("u2", AnalyseRequest("oli otya"), 2, urgent = true))
        assertEquals(CloudQueue.HOLD_RECHECK, q.pump(3, DeviceState(batteryPercent = 10, charging = false)))
    }

    @Test fun `backs off on failure and never grows without bound`() {
        val g = Gateway(fail = true)
        val q = CloudQueue(g, { _, _ -> }, maxJobs = 5)
        repeat(9) { q.enqueue(job(it)) }
        assertEquals(5, q.size())
        assertEquals(CloudQueue.INITIAL_BACKOFF, q.pump(0, DeviceState()))
        assertEquals(CloudQueue.INITIAL_BACKOFF - 1, q.pump(1, DeviceState()))
        g.fail = false
        q.pump(CloudQueue.INITIAL_BACKOFF, DeviceState())
        assertEquals(0, q.size())
    }
}

class LexiconUpdaterTest {
    private val bundle =
        """{"version":"1.2.0","files":[{"language":"lg","version":"1.2.0","entries":""" +
            """[{"term":"musiru","variants":["msiru"],"category":"insult","severity":"medium"}]}]}"""

    private class Gateway(val body: String, val sha: String) : AiGateway {
        var downloads = 0
        override fun analyse(request: AnalyseRequest) = error("unused")
        override fun feedback(request: FeedbackRequest) = Unit
        override fun latestLexicon() =
            ArtifactManifest("lexicon", "1.2.0", "http://x/lex.json", sha, body.length.toLong())
        override fun download(url: String): ByteArray {
            downloads++
            return body.toByteArray()
        }
    }

    @Test fun `installs a newer lexicon whose hash matches`() {
        val n = Normaliser()
        val u =
            LexiconUpdater(Gateway(bundle, Hashing.sha256Hex(bundle.toByteArray())), null, n, clock = {
                LexiconUpdater.WEEK *
                    2
            })
        assertTrue(u.maybeUpdate(DeviceState()))
        assertEquals("1.2.0", u.installedVersion)
        assertEquals("musiru", n.normalise("msiru"))
    }

    @Test fun `rejects a tampered download and waits for wifi`() {
        val g = Gateway(bundle, "0".repeat(64))
        val u = LexiconUpdater(g, null, Normaliser(), clock = { LexiconUpdater.WEEK * 2 })
        assertFalse(u.maybeUpdate(DeviceState()))
        assertEquals("0.0.0", u.installedVersion)
        assertFalse(u.maybeUpdate(DeviceState(unmetered = false))) // checked recently: no second try
        assertEquals(1, g.downloads)
    }
}
