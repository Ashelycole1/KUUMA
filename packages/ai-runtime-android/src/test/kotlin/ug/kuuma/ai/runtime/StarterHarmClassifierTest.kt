package ug.kuuma.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ai.ClassifyContext

class StarterHarmClassifierTest {
    private val c = StarterHarmClassifier()
    private fun v(text: String, lang: Language = Language.EN) = c.classify(text, ClassifyContext(language = lang))

    @Test fun `the bundled list loads`() {
        assertTrue(StarterHarmClassifier.bundled().size > 40)
    }

    @Test fun `harmful phrases and aimed insults are flagged with confidence`() {
        for ((text, category) in listOf(
            "i will kill you tomorrow" to HarmCategory.THREAT,
            "You're such an idiot" to HarmCategory.INSULT,
            "oli musilu nnyo" to HarmCategory.INSULT,
            "wewe mjinga" to HarmCategory.INSULT,
            "go die" to HarmCategory.SELF_HARM_ENCOURAGEMENT,
            "this is our secret ok" to HarmCategory.GROOMING,
            "idiot!" to HarmCategory.INSULT
        )) {
            val verdict = v(text)
            assertTrue(text, verdict.harmful && verdict.confidence >= 0.6)
            assertEquals(text, category, verdict.category)
        }
    }

    @Test fun `a hurtful word not aimed at anyone is only a maybe, so Sunflower is asked`() {
        for (text in listOf("this game is so stupid lol", "you kill me with these jokes")) {
            val verdict = v(text)
            assertTrue(text, verdict.harmful)
            assertTrue(text, verdict.confidence in 0.35..0.59)
        }
    }

    @Test fun `ordinary messages are clear, including the old test-word traps`() {
        for (text in listOf(
            "see you at school tomorrow",
            "the queue is killing me",
            "I got this as a gift from my aunt",
            "can you keep a secret about the surprise party",
            "classic movie night at mine",
            "the request to meet the teacher is at 3"
        )) {
            val verdict = v(text)
            assertFalse(text, verdict.harmful)
            assertTrue(text, verdict.confidence >= 0.6)
        }
    }

    @Test fun `unknown local-language messages are unsure, so Sunflower reads them`() {
        val verdict = v("Wasuze otya? Tugende ku ssomero", Language.LG)
        assertFalse(verdict.harmful)
        assertTrue(verdict.confidence < 0.6)
    }
}
