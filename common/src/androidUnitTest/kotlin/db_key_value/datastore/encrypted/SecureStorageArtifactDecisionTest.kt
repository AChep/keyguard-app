package db_key_value.datastore.encrypted

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureStorageArtifactDecisionTest {
    @Test
    fun `empty artifact state is a fresh installation`() {
        assertFalse(inventory().isUndecryptable())
    }

    @Test
    fun `master key without other artifacts resumes interrupted provisioning`() {
        assertFalse(inventory(masterKeyPresent = true).isUndecryptable())
    }

    @Test
    fun `master key and keyset are adopted as existing state`() {
        val inventory =
            inventory(
                keysetPresent = true,
                masterKeyPresent = true,
            )

        assertFalse(inventory.isUndecryptable())
    }

    @Test
    fun `ciphertext without keyset requires recovery`() {
        val inventory =
            inventory(
                ciphertextStores = setOf("master_key"),
                masterKeyPresent = true,
            )

        assertTrue(inventory.isUndecryptable())
    }

    @Test
    fun `ciphertext without master key requires recovery`() {
        val inventory =
            inventory(
                ciphertextStores = setOf("settings"),
                keysetPresent = true,
            )

        assertTrue(inventory.isUndecryptable())
    }

    @Test
    fun `keyset without master key requires recovery even without ciphertext`() {
        assertTrue(inventory(keysetPresent = true).isUndecryptable())
    }

    @Test
    fun `fully provisioned installation with ciphertext is healthy`() {
        val inventory =
            inventory(
                ciphertextStores = setOf("settings", "master_key"),
                keysetPresent = true,
                masterKeyPresent = true,
            )

        assertFalse(inventory.isUndecryptable())
    }

    @Test
    fun `keyset inspection loads preferences before checking the restored file`() {
        val directory = createTempDirectory("secure-storage-keyset-test").toFile()
        try {
            val preferencesFile = directory.resolve("keyset.xml")
            val backupFile = directory.resolve("keyset.xml.bak").apply { writeText("backup") }
            var containsCalls = 0

            val present =
                isKeysetPresent(preferencesFile) {
                    containsCalls += 1
                    assertTrue(backupFile.renameTo(preferencesFile))
                    true
                }

            assertTrue(present)
            assertEquals(1, containsCalls)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun inventory(
        ciphertextStores: Set<String> = emptySet(),
        keysetPresent: Boolean = false,
        masterKeyPresent: Boolean = false,
    ) = SecureStorageArtifactInventory(
        ciphertextStores = ciphertextStores,
        keysetPresent = keysetPresent,
        masterKeyPresent = masterKeyPresent,
    )
}
