package com.artemchep.keyguard.feature.home.vault.search.engine

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSearchTokenizerTest {
    private val tokenizer = DefaultSearchTokenizer()

    private inline fun <T> withLocale(
        locale: Locale,
        block: () -> T,
    ): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        return try {
            block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `tokenizes email with separators and lowercase`() {
        val out =
            tokenizer.tokenize(
                value = "User.Name+tag@Example.COM",
                profile = SearchTokenizerProfile.EMAIL,
            )
        assertTrue("user" in out.terms)
        assertTrue("example" in out.terms)
        assertTrue("com" in out.terms)
    }

    @Test
    fun `drops stop words from config`() {
        val out =
            tokenizer.tokenize(
                value = "the quick fox",
                profile = SearchTokenizerProfile.TEXT,
                config =
                    SearchTokenizerConfig(
                        stopWords = setOf("the"),
                        dropStopWords = true,
                    ),
            )
        assertEquals(listOf("quick", "fox"), out.terms)
    }

    @Test
    fun `folds human-readable accents into matching tokens`() {
        val out =
            tokenizer.tokenize(
                value = "José Müller Łukasz Straße İpek",
                profile = SearchTokenizerProfile.TEXT,
            )
        assertEquals(
            listOf("jose", "muller", "lukasz", "strasse", "ipek"),
            out.terms,
        )
        assertEquals(
            listOf("josé", "müller", "łukasz", "straße", "i", "pek"),
            out.exactTerms,
        )
    }

    @Test
    fun `normalizes decomposed unicode accents to the same token`() {
        val composed =
            tokenizer.tokenize(
                value = "José",
                profile = SearchTokenizerProfile.TEXT,
            )
        val decomposed =
            tokenizer.tokenize(
                value = "Jose\u0301",
                profile = SearchTokenizerProfile.TEXT,
            )

        assertEquals(composed.terms, decomposed.terms)
        assertEquals(composed.exactTerms, decomposed.exactTerms)
    }

    @Test
    fun `normalizes full-width text for matching`() {
        val out =
            tokenizer.tokenize(
                value = "Ｍüｌｌｅｒ",
                profile = SearchTokenizerProfile.TEXT,
            )

        assertEquals(listOf("muller"), out.terms)
        assertEquals(listOf("müller"), out.exactTerms)
    }

    @Test
    fun `keeps identifiers strict without folded aliases`() {
        val out =
            tokenizer.tokenize(
                value = "josé",
                profile = SearchTokenizerProfile.IDENTIFIER,
            )

        assertEquals(listOf("josé"), out.terms)
        assertEquals(listOf("josé"), out.exactTerms)
    }

    @Test
    fun `tokenizes Japanese text into multiple useful terms`() = withLocale(Locale.JAPANESE) {
        val out =
            tokenizer.tokenize(
                value = "東京都メモ",
                profile = SearchTokenizerProfile.TEXT,
            )

        assertTrue(out.terms.size > 1)
        assertTrue("東京都" in out.terms)
        assertTrue("メモ" in out.terms)
    }

    @Test
    fun `tokenizes Thai text into multiple useful terms`() = withLocale(Locale.forLanguageTag("th-TH")) {
        val out =
            tokenizer.tokenize(
                value = "รหัสผ่านไทย",
                profile = SearchTokenizerProfile.TEXT,
            )

        assertTrue(out.terms.size > 1)
        assertTrue(out.terms.any { it.startsWith("รห") })
        assertTrue("ไทย" in out.terms)
    }
}
