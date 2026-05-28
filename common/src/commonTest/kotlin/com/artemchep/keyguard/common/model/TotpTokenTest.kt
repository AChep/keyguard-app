package com.artemchep.keyguard.common.model

import arrow.core.Either
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}
