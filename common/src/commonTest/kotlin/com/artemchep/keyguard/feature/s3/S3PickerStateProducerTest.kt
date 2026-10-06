package com.artemchep.keyguard.feature.s3

import com.artemchep.keyguard.common.usecase.ListS3Directory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class S3PickerStateProducerTest {
    @Test
    fun `picker paths map to listing prefixes`() {
        assertEquals("", s3PickerPrefix(""))
        assertEquals("a/b/", s3PickerPrefix("a/b"))
    }

    @Test
    fun `folders are entered by their prefix without the trailing slash`() {
        val folder = s3PickerEntry(child("a/b/", name = "b", isFolder = true))
        val file = s3PickerEntry(child("a/vault.kdbx", name = "vault.kdbx"))

        assertEquals("a/b", folder.path)
        assertEquals("a/vault.kdbx", file.path)
        assertTrue(file.isAddressable)
    }

    @Test
    fun `files with repeated slashes retain their key and are addressable`() {
        val file = s3PickerEntry(child("a//vault.kdbx", name = "vault.kdbx"))

        assertEquals("a//vault.kdbx", file.path)
        assertTrue(file.isAddressable)
    }

    @Test
    fun `files that cannot be addressed as objects are not addressable`() {
        val file = s3PickerEntry(child("a/./vault.kdbx", name = "vault.kdbx"))

        assertFalse(file.isAddressable)
    }

    private fun child(
        key: String,
        name: String,
        isFolder: Boolean = false,
    ) = ListS3Directory.Child(
        key = key,
        name = name,
        isFolder = isFolder,
        size = null,
        lastModified = null,
    )
}
