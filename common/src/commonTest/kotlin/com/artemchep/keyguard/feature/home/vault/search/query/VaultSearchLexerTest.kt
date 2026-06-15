package com.artemchep.keyguard.feature.home.vault.search.query

import com.artemchep.keyguard.feature.home.vault.search.query.lexer.DefaultVaultSearchLexer
import com.artemchep.keyguard.feature.home.vault.search.query.lexer.SearchToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VaultSearchLexerTest {
    private val lexer = DefaultVaultSearchLexer()

    @Test
    fun `lexes text qualifier negation and quoted value`() {
        val out = lexer.lex("username:test -note:\"hello world\"")

        assertEquals(8, out.tokens.size)
        assertTrue(out.tokens[0] is SearchToken.Text)
        assertTrue(out.tokens[1] is SearchToken.Colon)
        assertTrue(out.tokens[2] is SearchToken.Text)
        assertTrue(out.tokens[3] is SearchToken.Whitespace)
        assertTrue(out.tokens[4] is SearchToken.Minus)
        assertTrue(out.tokens[5] is SearchToken.Text)
        assertTrue(out.tokens[6] is SearchToken.Colon)
        assertTrue(out.tokens[7] is SearchToken.Text)
    }

    @Test
    fun `keeps dash as text when not clause start`() {
        val out = lexer.lex("my-user")
        val token = out.tokens.single() as SearchToken.Text
        assertEquals("my-user", token.value)
    }

    @Test
    fun `parses escaped quote in quoted token`() {
        val out = lexer.lex("note:\"foo \\\"bar\\\" baz\"")
        val value = out.tokens.filterIsInstance<SearchToken.Text>().last()
        assertEquals("""foo "bar" baz""", value.value)
        assertTrue(value.quoted)
    }

    @Test
    fun `reports unterminated quote`() {
        val out = lexer.lex("note:\"unterminated")
        assertEquals(1, out.diagnostics.size)
        assertTrue(out.diagnostics.first().message.contains("Unterminated"))
    }
}
