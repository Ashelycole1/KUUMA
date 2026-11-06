package ug.kuuma.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ug.kuuma.ai.runtime.knit.CheckStatus
import ug.kuuma.ai.runtime.knit.CheckUnavailableException
import ug.kuuma.ai.runtime.knit.KnitHarmClassifier
import ug.kuuma.ai.runtime.knit.MessageCheck
import ug.kuuma.ai.runtime.knit.MessageChecker
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.ai.ClassifyContext

class KnitAdapterTest {
    @Test fun `typed abstention never becomes a safe verdict`() {
        for (status in CheckStatus.entries.filter { it != CheckStatus.VALID }) {
            val classifier = KnitHarmClassifier(MessageChecker { _, _ -> MessageCheck(status) })
            try {
                classifier.classify("Synthetic input", ClassifyContext(language = Language.EN))
                fail("An unavailable check must not become harmful=false")
            } catch (error: CheckUnavailableException) {
                assertEquals(status, error.status)
            }
        }
    }

    @Test fun `unknown and Luganda cannot reach the English scorer`() {
        val classifier = KnitHarmClassifier(MessageChecker { _, _ -> error("Must not run") })
        for (language in listOf(null, Language.LG, Language.SW, Language.NYN)) {
            try {
                classifier.classify("Synthetic input", ClassifyContext(language = language))
                fail("Must abstain")
            } catch (
                error: CheckUnavailableException
            ) {
                assertEquals(CheckStatus.UNSUPPORTED_LANGUAGE, error.status)
            }
        }
    }

    @Test fun `adapts prototype threshold and category without inventing certainty`() {
        val classifier = KnitHarmClassifier(
            MessageChecker { _, language ->
                assertEquals(Language.EN, language)
                MessageCheck(CheckStatus.VALID, true, 0.65, 0.01)
            }
        )
        val result = classifier.classify("Synthetic input", ClassifyContext(language = Language.EN))
        assertTrue(result.harmful)
        assertEquals(HarmCategory.THREAT, result.category)
        assertEquals(0.65, result.confidence, 0.0)
    }

    @Test fun `invalid scores do not leak through as certainty`() {
        val classifier =
            KnitHarmClassifier(MessageChecker { _, _ -> MessageCheck(CheckStatus.VALID, false, Double.NaN, 0.0) })
        try {
            classifier.classify("Synthetic input", ClassifyContext(language = Language.EN))
            fail("Invalid scores must be rejected")
        } catch (
            error: CheckUnavailableException
        ) {
            assertEquals(CheckStatus.INVALID, error.status)
        }
    }
}
