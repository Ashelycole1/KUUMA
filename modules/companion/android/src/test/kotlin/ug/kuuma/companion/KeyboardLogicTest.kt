package ug.kuuma.companion

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.ai.runtime.DummyReplyRiskChecker
import ug.kuuma.companion.content.ContentPack
import ug.kuuma.companion.content.CrisisPart
import ug.kuuma.companion.content.PlatformAction
import ug.kuuma.companion.keyboard.KeyKind
import ug.kuuma.companion.keyboard.KeyboardLayouts
import ug.kuuma.companion.keyboard.KindReplyChecker
import ug.kuuma.companion.keyboard.ReplyCheckRunner
import ug.kuuma.companion.keyboard.WordList
import ug.kuuma.companion.keyboard.currentWord
import ug.kuuma.contracts.AppSource
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ReplyRisk
import ug.kuuma.contracts.RiskySpan
import ug.kuuma.contracts.Severity

class KeyboardLogicTest {
    private val pack = ContentPack.bundled()

    @Test fun `bundled pack covers every category, app and crisis text`() {
        for (c in HarmCategory.entries) {
            assertTrue("$c has advice", pack.adviceKeys(c, Severity.HIGH).isNotEmpty())
        }
        for (app in AppSource.entries) {
            for (action in PlatformAction.entries) {
                assertTrue("$app $action", pack.platformSteps(app, action, Language.EN, null).isNotEmpty())
            }
        }
        for (part in CrisisPart.entries) assertTrue(pack.crisisText(part, Language.SW, null).isNotBlank())
        assertTrue(pack.helplines(Language.EN, null).any { it.number == "116" })
        assertFalse("not reviewed yet", pack.reviewed)
    }

    @Test fun `safe replies fall back to English`() {
        assertEquals(pack.safeReplies(Language.EN), pack.safeReplies(Language.NYN))
    }

    @Test fun `layouts are complete and Luganda has ŋ`() {
        for (layout in listOf(KeyboardLayouts.ENGLISH, KeyboardLayouts.LUGANDA, KeyboardLayouts.SYMBOLS)) {
            val kinds = layout.rows.flatten().map { it.kind }.toSet()
            for (k in listOf(KeyKind.DELETE, KeyKind.SPACE, KeyKind.ENTER, KeyKind.SHIELD, KeyKind.LANGUAGE)) {
                assertTrue("${layout.id} has $k", k in kinds)
            }
        }
        assertTrue(KeyboardLayouts.LUGANDA.rows.flatten().any { it.label == "ŋ" })
        assertEquals(Language.LG, KeyboardLayouts.next(Language.EN))
        assertEquals(Language.EN, KeyboardLayouts.next(Language.LG))
    }

    @Test fun `word suggestions are ranked, prefix-matched and keep capitalisation`() {
        val words = WordList(listOf("the", "they", "there", "then", "them"))
        assertEquals(listOf("they", "there", "then"), words.complete("the"))
        assertEquals(listOf("They", "There", "Then"), words.complete("The"))
        assertTrue(words.complete("").isEmpty())
        assertTrue(words.complete("zzz").isEmpty())
        assertTrue(WordList.forLanguage(Language.EN).size > 100)
        assertTrue(WordList.forLanguage(Language.LG).complete("web").contains("webale"))
        assertEquals(0, WordList.forLanguage(Language.SW).size)
    }

    @Test fun `current word is the letters before the cursor`() {
        assertEquals("hel", currentWord("say hel"))
        assertEquals("", currentWord("say "))
        assertEquals("don't", currentWord("I don't"))
    }

    private val now = Executor { it.run() }

    @Test fun `reply check flags a phone number and the safer wording removes it`() {
        var result: ReplyRisk? = null
        val runner = ReplyCheckRunner({ DummyReplyRiskChecker() }, now, { it.run() })
        val draft = "text me on 0772 123 456 later"
        runner.submit(draft, Language.EN) { result = it }
        val risk = result!!
        assertTrue(risk.risky)
        assertEquals("text me on later", ReplyCheckRunner.saferWording(draft, risk.spans))
    }

    @Test fun `hurtful words are marked kindly, in any language, whole words only`() {
        val checker = KindReplyChecker(DummyReplyRiskChecker())
        val draft = "you are so Stupid, shut   up"
        val risk = checker.check(draft, Language.EN)
        assertTrue(risk.risky)
        assertEquals(KindReplyChecker.BE_KIND, risk.spans.first().reasonKey)
        assertEquals("you are so,", ReplyCheckRunner.saferWording(draft, risk.spans))
        assertTrue(checker.check("ggwe musiru", Language.EN).risky) // Luganda, on the English keyboard
        assertFalse(checker.check("a classic study of dumbbells", Language.EN).risky)
        assertFalse(checker.check("see you tomorrow", Language.EN).risky)
        // The phone number check still works alongside it.
        val both = checker.check("idiot call 0772 123 456", Language.EN)
        assertEquals(2, both.spans.size)
        assertEquals("advice.reply.be_kind", both.suggestionKeys.first())
        // The advice text exists in the bundled pack.
        assertTrue(pack.adviceText(KindReplyChecker.BE_KIND, Language.EN, null).orEmpty().contains("hurtful"))
    }

    @Test fun `only the newest reply check is delivered`() {
        val queued = mutableListOf<Runnable>()
        val delivered = mutableListOf<ReplyRisk>()
        val runner = ReplyCheckRunner({ DummyReplyRiskChecker() }, { queued += it }, { it.run() })
        runner.submit("call 0772 123 456", Language.EN) { delivered += it }
        runner.submit("hello", Language.EN) { delivered += it }
        queued.forEach { it.run() }
        assertEquals(1, delivered.size)
        assertFalse(delivered.single().risky)
    }

    @Test fun `a crashing checker never breaks typing`() {
        var result: ReplyRisk? = null
        val boom = object : ug.kuuma.contracts.ai.ReplyRiskChecker {
            override val modelVersion = "boom"
            override fun check(draft: String, language: Language): ReplyRisk = error("model failed")
        }
        ReplyCheckRunner({ boom }, now, { it.run() }).submit("hi there", Language.EN) { result = it }
        assertFalse(result!!.risky)
    }

    @Test fun `bad spans from a checker are dropped or merged`() {
        val spans = listOf(
            RiskySpan(5, 3, "advice.reply.no_location"),
            RiskySpan(0, 99, "advice.reply.no_location"),
            RiskySpan(0, 4, "advice.reply.no_location"),
            RiskySpan(2, 6, "advice.reply.no_location")
        )
        assertEquals(listOf(RiskySpan(0, 6, "advice.reply.no_location")), ReplyCheckRunner.validSpans(spans, 10))
    }
}
