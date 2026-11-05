package ug.kuuma.ai.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.Severity

class DummiesTest {
    @Test fun `flags a test word as high severity`() {
        val v = DummyHarmClassifier().classify("Oli musiru nnyo")
        assertTrue(v.harmful)
        assertEquals(HarmCategory.INSULT, v.category)
        assertEquals(Severity.HIGH, v.severity)
    }

    @Test fun `leaves ordinary messages alone`() {
        val v = DummyHarmClassifier().classify("Wasuze otya? Homework y'olwaleero ebadde ki?")
        assertFalse(v.harmful)
        assertEquals(null, v.category)
    }

    @Test fun `reply checker flags phone numbers`() {
        val risk = DummyReplyRiskChecker().check("ok call me on 0772 123 456 later", Language.EN)
        assertTrue(risk.risky)
        assertEquals(14L, risk.spans.single().start)
        assertFalse(DummyReplyRiskChecker().check("see you at 16:05", Language.EN).risky)
    }
}
