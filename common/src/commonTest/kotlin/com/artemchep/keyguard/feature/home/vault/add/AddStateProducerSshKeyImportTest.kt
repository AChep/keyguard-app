package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AddStateProducerSshKeyImportTest {
    @Test
    fun `successful ssh key file import returns imported key`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://ssh/key"),
            name = "id_ed25519",
            size = 1024L,
        )
        val expectedKeyPair = createKeyPair()
        var importedKeyPair: KeyPair? = null

        handleSshKeyFileImport(
            info = info,
            readBytes = { uri ->
                assertEquals("content://ssh/key", uri)
                "ssh-key-content".encodeToByteArray()
            },
            importSshKey = { request ->
                assertContentEquals("ssh-key-content".encodeToByteArray(), request.content)
                assertEquals("id_ed25519", request.fileName)
                assertEquals(null, request.passphrase)
                SshKeyImportResult.Success(expectedKeyPair)
            },
            onSuccess = { keyPair ->
                importedKeyPair = keyPair
            },
            onNeedsPassphrase = { _, _, _ ->
                fail("Encrypted-key path should not be used for a successful import.")
            },
            onImportError = { _ ->
                fail("Import error path should not be used for a successful import.")
            },
            onReadError = {
                fail("Read error path should not be used for a successful import.")
            },
        )

        assertEquals(expectedKeyPair, importedKeyPair)
    }

    @Test
    fun `encrypted ssh key file import triggers passphrase flow`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://ssh/key"),
            name = "id_ed25519",
            size = 1024L,
        )
        var passphraseRequest: Triple<SshKeyImportResult.NeedsPassphrase, String?, ByteArray>? = null

        handleSshKeyFileImport(
            info = info,
            readBytes = {
                "encrypted-key-content".encodeToByteArray()
            },
            importSshKey = {
                SshKeyImportResult.NeedsPassphrase("OpenSSH")
            },
            onSuccess = {
                fail("Success path should not be used for an encrypted key without a passphrase.")
            },
            onNeedsPassphrase = { result, fileName, content ->
                passphraseRequest = Triple(result, fileName, content.copyOf())
            },
            onImportError = { _ ->
                fail("Import error path should not be used for the passphrase flow.")
            },
            onReadError = {
                fail("Read error path should not be used for the passphrase flow.")
            },
        )

        assertEquals(SshKeyImportResult.NeedsPassphrase("OpenSSH"), passphraseRequest?.first)
        assertEquals("id_ed25519", passphraseRequest?.second)
        assertContentEquals("encrypted-key-content".encodeToByteArray(), passphraseRequest?.third)
    }

    @Test
    fun `ssh key file import reports read failures before importing`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://ssh/key"),
            name = "id_ed25519",
            size = 1024L,
        )
        var importCalled = false
        var readErrorShown = false

        handleSshKeyFileImport(
            info = info,
            readBytes = {
                error("boom")
            },
            importSshKey = {
                importCalled = true
                SshKeyImportResult.NeedsPassphrase("OpenSSH")
            },
            onSuccess = {
                fail("Success path should not be used when reading the file fails.")
            },
            onNeedsPassphrase = { _, _, _ ->
                fail("Passphrase path should not be used when reading the file fails.")
            },
            onImportError = { _ ->
                fail("Import error path should not be used when reading the file fails.")
            },
            onReadError = {
                readErrorShown = true
            },
        )

        assertFalse(importCalled)
        assertTrue(readErrorShown)
    }

    @Test
    fun `raw file bytes survive suspended passphrase retry and are then cleared`() = kotlinx.coroutines.test.runTest {
        val expected = byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte())
        val content = expected.copyOf()
        handleSshKeyFileImport(
            info = FilePickerResult(leParseUri("content://ssh/key"), "key.ppk", 4L),
            readBytes = { content },
            importSshKey = { request ->
                assertContentEquals(expected, request.content)
                SshKeyImportResult.NeedsPassphrase("PuTTY")
            },
            onSuccess = { fail("Expected passphrase prompt") },
            onNeedsPassphrase = { _, _, bytes ->
                delay(1)
                assertContentEquals(expected, bytes)
            },
            onImportError = { fail("Unexpected import error") },
            onReadError = { fail("Unexpected read error") },
        )
        assertContentEquals(ByteArray(content.size), content)
    }

    @Test
    fun `cancelling passphrase retry clears file bytes`() = kotlinx.coroutines.test.runTest {
        val content = byteArrayOf(0xe9.toByte())
        assertFailsWith<CancellationException> {
            handleSshKeyFileImport(
                info = FilePickerResult(leParseUri("content://ssh/key"), "key.ppk", 1L),
                readBytes = { content },
                importSshKey = { SshKeyImportResult.NeedsPassphrase("PuTTY") },
                onSuccess = { fail("Expected passphrase prompt") },
                onNeedsPassphrase = { _, _, _ -> throw CancellationException() },
                onImportError = { fail("Unexpected import error") },
                onReadError = { fail("Unexpected read error") },
            )
        }
        assertContentEquals(byteArrayOf(0), content)
    }
}

private fun createKeyPair() = KeyPair(
    type = KeyPair.Type.ED25519,
    privateKey = KeyPair.KeyParameter(
        encoded = byteArrayOf(1, 2, 3),
        type = KeyPair.Type.ED25519,
        ssh = "PRIVATE",
        fingerprint = "private-fingerprint",
    ),
    publicKey = KeyPair.KeyParameter(
        encoded = byteArrayOf(4, 5, 6),
        type = KeyPair.Type.ED25519,
        ssh = "PUBLIC",
        fingerprint = "public-fingerprint",
    ),
)
