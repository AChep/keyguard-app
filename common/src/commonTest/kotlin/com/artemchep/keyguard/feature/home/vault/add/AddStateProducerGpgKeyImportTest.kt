package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.toGpgKeyMaterial
import com.artemchep.keyguard.common.model.withGpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.test.generatedGpgKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AddStateProducerGpgKeyImportTest {
    @Test
    fun `direct import result cannot overwrite a newer expiration mutation`() {
        val original = generatedGpgKey()
        val sink = MutableStateFlow(original)
        val mutations = GpgKeyMutationGuard(sink)
        val importSnapshot = mutations.snapshot()
        val expirationSnapshot = mutations.snapshot()
        val renewedMaterial = original.toGpgKeyMaterial().copy(
            privateKeyArmored = "RENEWED PRIVATE",
            publicKeyArmored = "RENEWED PUBLIC",
        )

        assertTrue(mutations.commitMaterial(expirationSnapshot, renewedMaterial))
        assertFalse(
            mutations.commitReplacement(
                snapshot = importSnapshot,
                value = original.copy(
                    privateKeyArmored = "STALE IMPORT PRIVATE",
                    publicKeyArmored = "STALE IMPORT PUBLIC",
                ),
            ),
        )
        assertEquals(original.withGpgKeyMaterial(renewedMaterial), sink.value)
    }

    @Test
    fun `expiration result cannot overwrite a newer import mutation`() {
        val original = generatedGpgKey()
        val sink = MutableStateFlow(original)
        val mutations = GpgKeyMutationGuard(sink)
        val expirationSnapshot = mutations.snapshot()
        val importSnapshot = mutations.snapshot()
        val imported = original.copy(
            privateKeyArmored = "IMPORTED PRIVATE",
            publicKeyArmored = "IMPORTED PUBLIC",
        )
        val staleRenewal = original.toGpgKeyMaterial().copy(
            privateKeyArmored = "STALE RENEWED PRIVATE",
            publicKeyArmored = "STALE RENEWED PUBLIC",
        )

        assertTrue(mutations.commitReplacement(importSnapshot, imported))
        assertFalse(mutations.commitMaterial(expirationSnapshot, staleRenewal))
        assertEquals(imported, sink.value)
    }

    @Test
    fun `only one overlapping import can commit a shared snapshot`() {
        val original = generatedGpgKey()
        val sink = MutableStateFlow(original)
        val mutations = GpgKeyMutationGuard(sink)
        val firstSnapshot = mutations.snapshot()
        val secondSnapshot = mutations.snapshot()
        val firstImport = original.copy(publicKeyArmored = "FIRST IMPORT PUBLIC")
        val secondImport = original.copy(publicKeyArmored = "SECOND IMPORT PUBLIC")

        assertTrue(mutations.commitReplacement(firstSnapshot, firstImport))
        assertFalse(mutations.commitReplacement(secondSnapshot, secondImport))
        assertEquals(firstImport, sink.value)
    }

    @Test
    fun `prepared reconciliation cannot commit after the editor changes`() {
        val original = generatedGpgKey()
        val sink = MutableStateFlow(original)
        val mutations = GpgKeyMutationGuard(sink)
        val importSnapshot = mutations.snapshot()
        val prepared = original.copy(publicKeyArmored = "PREPARED PUBLIC")
        val edited = original.copy(publicKeyArmored = "EDITED PUBLIC")

        mutations.replace(edited)

        assertFalse(mutations.commitReplacement(importSnapshot, prepared))
        assertEquals(edited, sink.value)
    }

    @Test
    fun `passphrase import result cannot commit after an ABA key mutation`() {
        val original = generatedGpgKey()
        val sink = MutableStateFlow(original)
        val mutations = GpgKeyMutationGuard(sink)
        val passphraseSnapshot = mutations.snapshot()

        mutations.replace(original.copy(publicKeyArmored = "INTERVENING PUBLIC"))
        mutations.replace(original)

        assertFalse(
            mutations.commitReplacement(
                snapshot = passphraseSnapshot,
                value = original.copy(
                    privateKeyArmored = "UNLOCKED PRIVATE",
                    publicKeyArmored = "STALE PUBLIC",
                ),
            ),
        )
        assertEquals(original, sink.value)
    }

    @Test
    fun `successful gpg key file import returns imported key`() = kotlinx.coroutines.test.runTest {
        val info = FilePickerResult(
            uri = leParseUri("content://gpg/key"),
            name = "key.asc",
            size = 1024L,
        )
        val expectedKey = generatedGpgKey()
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
