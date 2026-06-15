package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.KeyPair
import com.artemchep.keyguard.common.service.crypto.SshKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.SshKeyImportResult
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlin.test.Test
import kotlin.test.assertEquals
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
            readText = { uri ->
                assertEquals("content://ssh/key", uri)
                "ssh-key-content"
            },
            importSshKey = { request ->
                assertEquals(
                    SshKeyImportRequest(
                        content = "ssh-key-content",
                        fileName = "id_ed25519",
                        passphrase = null,
                    ),
                    request,
                )
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
        var passphraseRequest: Triple<SshKeyImportResult.NeedsPassphrase, String?, String>? = null

        handleSshKeyFileImport(
            info = info,
            readText = {
                "encrypted-key-content"
            },
            importSshKey = {
                SshKeyImportResult.NeedsPassphrase("OpenSSH")
            },
            onSuccess = {
                fail("Success path should not be used for an encrypted key without a passphrase.")
            },
            onNeedsPassphrase = { result, fileName, content ->
                passphraseRequest = Triple(result, fileName, content)
            },
            onImportError = { _ ->
                fail("Import error path should not be used for the passphrase flow.")
            },
            onReadError = {
                fail("Read error path should not be used for the passphrase flow.")
            },
        )

        assertEquals(
            Triple(
                SshKeyImportResult.NeedsPassphrase("OpenSSH"),
                "id_ed25519",
                "encrypted-key-content",
            ),
            passphraseRequest,
        )
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
            readText = {
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
