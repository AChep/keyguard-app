package com.artemchep.keyguard.common.service.s3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class S3KeyPrefixTest {
    @Test
    fun `prefix is trimmed and ends with a slash`() {
        assertEquals("", normalizeS3Prefix(null))
        assertEquals("", normalizeS3Prefix(" / "))
        assertEquals("backups/", normalizeS3Prefix(" backups "))
        assertEquals("a/b/", normalizeS3Prefix("/a//b/"))
    }

    @Test
    fun `prefix validation rejects dot segments`() {
        assertTrue(isValidS3Prefix(""))
        assertTrue(isValidS3Prefix("a/b/"))
        assertFalse(isValidS3Prefix("a/b"))
        assertFalse(isValidS3Prefix("a/../"))
        assertFalse(isValidS3Prefix("./"))
    }

    @Test
    fun `keys are joined and stripped`() {
        assertEquals("backups/repo.zip", joinS3Key("backups/", "repo.zip"))
        assertEquals("repo.zip", stripS3Prefix("backups/", "backups/repo.zip"))
        assertNull(stripS3Prefix("backups/", "other/repo.zip"))
    }
}
