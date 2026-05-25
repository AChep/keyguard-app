package com.artemchep.keyguard.android.autofill.v2.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {
    @Test
    fun `short password keywords do not match inside longer tokens`() {
        assertFalse(match("passenger") has KeywordTag.PASSWORD)
        assertFalse(match("shipping address") has KeywordTag.PASSWORD)
    }

    @Test
    fun `password and pin identifiers still match after normalization`() {
        assertTrue(match("userPassword") has KeywordTag.PASSWORD)
        assertTrue(match("password2") has KeywordTag.PASSWORD)
        assertTrue(match("pinCode") has KeywordTag.PASSWORD)
        assertTrue(match("pincode") has KeywordTag.PASSWORD)
        assertTrue(match("pin-code") has KeywordTag.PASSWORD)
        assertTrue(match("pin_1") has KeywordTag.PASSWORD)
        assertTrue(match("pwdfield") has KeywordTag.PASSWORD)
        assertTrue(match("pswfield") has KeywordTag.PASSWORD)
    }

    @Test
    fun `digit-led otp tokens still match after normalization`() {
        assertTrue(match("2fa") has KeywordTag.OTP)
        assertTrue(match("2faCode") has KeywordTag.OTP)
        assertTrue(match("2facode") has KeywordTag.OTP)
        assertTrue(match("otpcode") has KeywordTag.OTP)
        assertTrue(match("smsotpcode") has KeywordTag.OTP)
        assertTrue(match("emailotpcode") has KeywordTag.OTP)
    }

    @Test
    fun `short phone keyword matches identifier token but not embedded text`() {
        assertTrue(match("noHp") has KeywordTag.PHONE)
        assertTrue(match("nohp") has KeywordTag.PHONE)
        assertFalse(match("phpVersion") has KeywordTag.PHONE)
    }

    @Test
    fun `short name search and region keywords require token boundaries`() {
        assertFalse(match("address") has KeywordTag.NAME)
        assertFalse(match("nominal") has KeywordTag.NAME)
        assertFalse(match("param") has KeywordTag.SEARCH)
        assertFalse(match("billing") has KeywordTag.REGION)

        assertTrue(match("ad soyad") has KeywordTag.NAME)
        assertTrue(match("nom") has KeywordTag.FAMILY_NAME)
        assertTrue(match("ara") has KeywordTag.SEARCH)
        assertTrue(match("il") has KeywordTag.REGION)
    }

    private fun match(text: String): Long = KeywordMatcher.match(normalizeSignalText(text))
}
