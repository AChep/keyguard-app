package com.artemchep.keyguard.feature.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3FormValidationTest {
    private val purpose = S3SettingsRoute.Purpose.KeePassDatabase
    private val valid = S3FormInput("", "", "vaults", "vault.kdbx", "AKID", "secret", true)

    @Test
    fun `initial editing defers feedback until blur and ignores untouched fields`() {
        val input = valid.copy(bucket = "", secretAccessKey = "")
        val errors = s3FormErrors(input, purpose)
        val editing = S3FormValidation().edit("bucket", errors)
        assertTrue(editing.errors.isEmpty())
        assertTrue(editing.blur("secretAccessKey", errors).errors.isEmpty())
        val blurred = editing.blur("bucket", errors)
        assertEquals(listOf(S3FormError.BucketRequired), blurred.errors)
        assertNull(blurred.focusField)
    }

    @Test
    fun `repeated invalid submissions always request focus without exposing the secret`() {
        val errors = s3FormErrors(valid.copy(secretAccessKey = ""), purpose)
        val first = S3FormValidation().submit(errors)
        val second = first.submit(errors)
        assertEquals(listOf(S3FormError.SecretAccessKeyRequired), second.errors)
        assertEquals("secretAccessKey", second.focusField)
        assertEquals(first.request + 1, second.request)
    }

    @Test
    fun `submit focuses first invalid field in visual order and reports all errors`() {
        val errors = s3FormErrors(valid.copy(path = "vault.txt", accessKeyId = "", secretAccessKey = ""), purpose)
        val submitted = S3FormValidation().submit(errors)
        assertEquals("key", submitted.focusField)
        assertEquals(3, submitted.errors.size)
        // Preserve the existing validator contract for other platforms and saved backups.
        assertEquals(
            S3FormError.AccessKeyIdRequired,
            validateS3Form(valid.copy(path = "vault.txt", accessKeyId = ""), purpose),
        )
        assertNull(validateS3Form(valid.copy(secretAccessKey = ""), purpose, hasSavedSecret = true))
    }

    @Test
    fun `visible errors persist while invalid and clear during correction without a focus request`() {
        val invalid = valid.copy(path = "vault.txt", secretAccessKey = "")
        val submitted = S3FormValidation().submit(s3FormErrors(invalid, purpose))
        val stillInvalid = submitted.edit("key", s3FormErrors(invalid.copy(path = "vault.kdb"), purpose))
        assertEquals(submitted.errors, stillInvalid.errors)
        val keyFixed = stillInvalid.edit("key", s3FormErrors(valid.copy(secretAccessKey = ""), purpose))
        assertEquals(listOf(S3FormError.SecretAccessKeyRequired), keyFixed.errors)
        val allFixed = keyFixed.edit("secretAccessKey", s3FormErrors(valid, purpose))
        assertTrue(allFixed.errors.isEmpty())
        assertEquals(submitted.request, allFixed.request)
        assertNull(allFixed.submit(s3FormErrors(valid, purpose)).focusField)
    }
}
