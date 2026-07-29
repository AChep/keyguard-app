package com.artemchep.keyguard.common.model

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TotpTokenTest {
    @Test
    fun `parse accepts nine digit totp token`() {
        val result = TotpToken.parse("otpauth://totp/test?secret=valid&digits=9")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertEquals(9, token.digits)
    }

    @Test
    fun `parse rejects ten digit totp token`() {
        val result = TotpToken.parse("otpauth://totp/test?secret=valid&digits=10")

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<IllegalArgumentException>(left.value)
    }

    @Test
    fun `parse rejects md5 totp token`() {
        val result = TotpToken.parse("otpauth://totp/test?secret=valid&algorithm=md5")

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<IllegalArgumentException>(left.value)
    }

    @Test
    fun `parse extracts the label account name and issuer prefix`() {
        val result = TotpToken.parse("otpauth://totp/ACME%20Co:alice@example.com?secret=valid")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertEquals("alice@example.com", token.username)
        assertEquals("ACME Co", token.issuer)
    }

    @Test
    fun `parse prefers the issuer query parameter over the label prefix`() {
        val result = TotpToken.parse("otpauth://totp/Legacy:alice?secret=valid&issuer=ACME")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertEquals("alice", token.username)
        assertEquals("ACME", token.issuer)
    }

    @Test
    fun `parse handles a label without an issuer prefix`() {
        val result = TotpToken.parse("otpauth://totp/alice?secret=valid")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertEquals("alice", token.username)
        assertNull(token.issuer)
    }

    @Test
    fun `parse splits the label on the first colon only`() {
        val result = TotpToken.parse("otpauth://totp/a:b:c?secret=valid")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertEquals("a", token.issuer)
        assertEquals("b:c", token.username)
    }

    @Test
    fun `parse keeps username and issuer null for bare secrets`() {
        val result = TotpToken.parse("JBSWY3DPEHPK3PXP")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.TotpAuth>(right.value)
        assertNull(token.username)
        assertNull(token.issuer)
    }

    @Test
    fun `parse accepts nine digit hotp token`() {
        val result = TotpToken.parse("otpauth://hotp/test?secret=valid&counter=1&digits=9")

        val right = assertIs<Either.Right<TotpToken>>(result)
        val token = assertIs<TotpToken.HotpAuth>(right.value)
        assertEquals(9, token.digits)
    }

    @Test
    fun `parse rejects ten digit hotp token`() {
        val result = TotpToken.parse("otpauth://hotp/test?secret=valid&counter=1&digits=10")

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<IllegalArgumentException>(left.value)
    }

    @Test
    fun `parse rejects md5 hotp token`() {
        val result = TotpToken.parse("otpauth://hotp/test?secret=valid&counter=1&algorithm=md5")

        val left = assertIs<Either.Left<Throwable>>(result)
        assertIs<IllegalArgumentException>(left.value)
    }
}
