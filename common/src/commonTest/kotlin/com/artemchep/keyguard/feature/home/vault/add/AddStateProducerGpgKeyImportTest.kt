package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentKeyMetadata
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AddStateProducerGpgKeyImportTest {
    @Test
    fun `successful gpg key file import returns imported key`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://gpg/key"),
            name = "key.asc",
            size = 1024L,
        )
        val expectedKey = createGpgKey()
        var importedKey: GeneratedGpgKey? = null

        handleGpgKeyFileImport(
            info = info,
            readText = { uri ->
                assertEquals("content://gpg/key", uri)
                "gpg-key-content"
            },
            importGpgKey = { request ->
                assertEquals(
                    GpgKeyImportRequest(
                        content = "gpg-key-content",
                        fileName = "key.asc",
                        passphrase = null,
                    ),
                    request,
                )
                GpgKeyImportResult.Success(expectedKey)
            },
            onSuccess = { key ->
                importedKey = key
            },
            onNeedsPassphrase = { _, _, _ ->
                fail("Passphrase path should not be used for a successful import.")
            },
            onImportError = { _ ->
                fail("Import error path should not be used for a successful import.")
            },
            onReadError = {
                fail("Read error path should not be used for a successful import.")
            },
        )

        assertEquals(expectedKey, importedKey)
    }

    @Test
    fun `encrypted gpg key file import triggers passphrase flow`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://gpg/key"),
            name = "private.asc",
            size = 1024L,
        )
        var passphraseRequest: Triple<GpgKeyImportResult.NeedsPassphrase, String?, String>? = null

        handleGpgKeyFileImport(
            info = info,
            readText = {
                "encrypted-gpg-key-content"
            },
            importGpgKey = {
                GpgKeyImportResult.NeedsPassphrase("OpenPGP")
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
                GpgKeyImportResult.NeedsPassphrase("OpenPGP"),
                "private.asc",
                "encrypted-gpg-key-content",
            ),
            passphraseRequest,
        )
    }

    @Test
    fun `gpg key file import reports read failures before importing`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://gpg/key"),
            name = "key.asc",
            size = 1024L,
        )
        var importCalled = false
        var readErrorShown = false

        handleGpgKeyFileImport(
            info = info,
            readText = {
                error("boom")
            },
            importGpgKey = {
                importCalled = true
                GpgKeyImportResult.NeedsPassphrase("OpenPGP")
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

private fun createGpgKey() = GeneratedGpgKey(
    privateKeyArmored = "PRIVATE",
    publicKeyArmored = "PUBLIC",
    fingerprint = "FINGERPRINT",
    metadata = GpgAgentKeyMetadata(),
    userId = "Keyguard Test <test@example.com>",
    typeLabel = "OpenPGP",
)
