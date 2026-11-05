package ug.kuuma.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ug.kuuma.contracts.AgeBracket
import ug.kuuma.contracts.ContractPatterns
import ug.kuuma.contracts.GroomingStage
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Language
import ug.kuuma.contracts.RecipientType
import ug.kuuma.contracts.Severity

/** Fails if kuuma-lang values and the generated contract enums ever differ. */
class KuumaLangTest {
    @Test fun `values equal contract enums`() {
        assertEquals(HarmCategory.entries.map { it.wire }, KuumaLang.ids("harm-categories"))
        assertEquals(Severity.entries.map { it.wire }, KuumaLang.ids("severity"))
        assertEquals(GroomingStage.entries.map { it.wire }, KuumaLang.ids("grooming-stages"))
        assertEquals(AgeBracket.entries.map { it.wire }, KuumaLang.ids("age-brackets"))
        assertEquals(RecipientType.entries.map { it.wire }, KuumaLang.ids("recipient-types"))
        assertEquals(Language.entries.map { it.wire }, KuumaLang.ids("languages"))
    }

    @Test fun `advice keys match the contract pattern`() {
        KuumaLang.ids("advice-keys").forEach { assertTrue(it, ContractPatterns.ADVICE_KEY.matches(it)) }
    }

    @Test fun `labels fall back to English`() {
        assertEquals("Grooming", KuumaLang.entries("harm-categories").first { it.id == "grooming" }.label("lg"))
        assertTrue(KuumaLang.severityRank("high") > KuumaLang.severityRank("medium"))
    }
}
