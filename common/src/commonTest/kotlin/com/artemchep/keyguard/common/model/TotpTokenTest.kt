package com.artemchep.keyguard.common.model

import arrow.core.Either
import com.artemchep.keyguard.common.exception.OtpEmptySecretKeyException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TotpTokenTest {
    @Test
    fun `parse recognizes keepassxc steam encoder and keeps the original uri`() {
        val raw = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&period=30&digits=5&issuer=Steam&encoder=steam"

        val token = assertIs<TotpToken.SteamAuth>(TotpToken.parse(raw).getOrNull())

        assertEquals(raw, token.raw)
        assertEquals("JBSWY3DPEHPK3PXP", token.keyBase32)
        assertEquals(CryptoHashAlgorithm.SHA_1, token.algorithm)
        assertEquals(5, token.digits)
        assertEquals(30L, token.period)
    }

    @Test
    fun `parse uses steam defaults and accepts encoder casing`() {
        listOf("steam", "Steam", "STEAM").forEach { encoder ->
            val raw = "otpauth://totp/Account?secret=JBSWY3DPEHPK3PXP&encoder=$encoder"
            val token = assertIs<TotpToken.SteamAuth>(TotpToken.parse(raw).getOrNull())

            assertEquals(5, token.digits)
            assertEquals(30L, token.period)
        }
    }

    @Test
    fun `parse preserves supported steam hash algorithms`() {
        listOf(
            "SHA1" to CryptoHashAlgorithm.SHA_1,
            "SHA256" to CryptoHashAlgorithm.SHA_256,
            "SHA512" to CryptoHashAlgorithm.SHA_512,
        ).forEach { (name, algorithm) ->
            val raw = "otpauth://totp/Account?secret=JBSWY3DPEHPK3PXP&encoder=steam&algorithm=$name"
            val token = assertIs<TotpToken.SteamAuth>(TotpToken.parse(raw).getOrNull())

            assertEquals(algorithm, token.algorithm)
        }
    }

    @Test
    fun `parse rejects incompatible steam parameters`() {
        listOf(
            "digits=6", "digits=0", "digits=10", "digits=abc",
            "period=60", "period=0", "period=-1", "period=abc", "period=",
            "algorithm=md5",
        ).forEach { parameter ->
            val raw = "otpauth://totp/Account?secret=JBSWY3DPEHPK3PXP&encoder=steam&$parameter"
            val left = assertIs<Either.Left<Throwable>>(TotpToken.parse(raw), parameter)

            assertIs<IllegalArgumentException>(left.value, parameter)
        }
    }

    @Test
    fun `parse rejects empty steam secrets`() {
        listOf("", "&secret=", "&secret=%20").forEach { secret ->
            val result = TotpToken.parse("otpauth://totp/Account?encoder=steam$secret")
            val left = assertIs<Either.Left<Throwable>>(result)

            assertIs<OtpEmptySecretKeyException>(left.value)
        }
    }

    @Test
    fun `steam labels and five digits do not select the steam encoder`() {
        val raw = "otpauth://totp/Steam:alice?secret=JBSWY3DPEHPK3PXP&issuer=Steam&digits=5"
        val token = assertIs<TotpToken.TotpAuth>(TotpToken.parse(raw).getOrNull())

        assertEquals(5, token.digits)
        assertEquals("Steam", token.issuer)
    }

    @Test
    fun `steam encoder does not change hotp parsing`() {
        val raw = "otpauth://hotp/Account?secret=JBSWY3DPEHPK3PXP&counter=7&encoder=steam"
        val token = assertIs<TotpToken.HotpAuth>(TotpToken.parse(raw).getOrNull())

        assertEquals(7L, token.counter)
        assertEquals(6, token.digits)
    }

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
