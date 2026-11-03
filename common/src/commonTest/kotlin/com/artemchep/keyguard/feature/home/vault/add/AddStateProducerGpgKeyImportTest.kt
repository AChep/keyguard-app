package com.artemchep.keyguard.feature.home.vault.add

import com.artemchep.keyguard.common.model.GeneratedGpgKey
import com.artemchep.keyguard.common.model.toGpgKeyMaterial
import com.artemchep.keyguard.common.model.withGpgKeyMaterial
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportRequest
import com.artemchep.keyguard.common.service.crypto.GpgKeyImportResult
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.platform.leParseUri
import com.artemchep.keyguard.provider.bitwarden.upload.assertKeyCleared
import com.artemchep.keyguard.test.generatedGpgKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
    fun `binary gpg key file import preserves every byte and returns imported key`() = kotlinx.coroutines.test.runTest {
        val expectedKey = generatedGpgKey()
        var importedKey: GeneratedGpgKey? = null
        val content = binaryKeyBytes()

        importBinaryFile(
            content = content,
            readBytes = { uri ->
                assertEquals("content://gpg/key", uri)
                content
            },
            importGpgKey = { request ->
                assertContentEquals(binaryKeyBytes(), request.content)
                assertEquals("private.gpg", request.fileName)
                assertEquals(null, request.passphrase)
                GpgKeyImportResult.Success(expectedKey)
            },
            onSuccess = { key ->
                importedKey = key
            },
        )

        assertEquals(expectedKey, importedKey)
        assertKeyCleared(content)
    }

    @Test
    fun `encrypted gpg key file import triggers passphrase flow`() = kotlinx.coroutines.test.runTest {
        val content = binaryKeyBytes()
        var passphraseRequest: Triple<GpgKeyImportResult.NeedsPassphrase, String?, ByteArray>? = null

        importBinaryFile(
            content = content,
            onNeedsPassphrase = { result, fileName, content ->
                passphraseRequest = Triple(result, fileName, content.copyOf())
            },
        )

        assertEquals(GpgKeyImportResult.NeedsPassphrase("OpenPGP"), passphraseRequest?.first)
        assertEquals("private.gpg", passphraseRequest?.second)
        assertContentEquals(binaryKeyBytes(), passphraseRequest?.third)
        assertKeyCleared(content)
    }

    @Test
    fun `gpg key file import reports read failures before importing`() = kotlinx.coroutines.test.runTest {
        var importCalled = false
        var readErrorShown = false

        importBinaryFile(
            content = binaryKeyBytes(),
            readBytes = {
                error("boom")
            },
            importGpgKey = {
                importCalled = true
                GpgKeyImportResult.NeedsPassphrase("OpenPGP")
            },
            onReadError = {
                readErrorShown = true
            },
        )

        assertFalse(importCalled)
        assertTrue(readErrorShown)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `binary content survives the passphrase wait`() = kotlinx.coroutines.test.runTest {
        val content = binaryKeyBytes()
        val passphrase = CompletableDeferred<String>()
        val job = launch {
            importBinaryFile(
                content = content,
                onNeedsPassphrase = { _, _, bytes ->
                    val supplied = passphrase.await()
                    assertEquals("passphrase", supplied)
                    assertContentEquals(binaryKeyBytes(), bytes)
                },
            )
        }

        runCurrent()
        assertContentEquals(binaryKeyBytes(), content)
        passphrase.complete("passphrase")
        job.join()
        assertKeyCleared(content)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cancelling a pending passphrase import erases binary content`() = kotlinx.coroutines.test.runTest {
        val content = binaryKeyBytes()
        val job = launch {
            importBinaryFile(
                content = content,
                onNeedsPassphrase = { _, _, _ -> awaitCancellation() },
            )
        }

        runCurrent()
        assertContentEquals(binaryKeyBytes(), content)
        job.cancelAndJoin()
        assertKeyCleared(content)
    }

    @Test
    fun `import failures erase binary content`() = kotlinx.coroutines.test.runTest {
        val content = binaryKeyBytes()
        assertFailsWith<IllegalStateException> {
            importBinaryFile(
                content = content,
                importGpgKey = { error("import failed") },
            )
        }
        assertKeyCleared(content)
    }

    @Test
    fun `read cancellation is propagated without reporting a read error`() = kotlinx.coroutines.test.runTest {
        assertFailsWith<CancellationException> {
            importBinaryFile(
                content = binaryKeyBytes(),
                readBytes = { throw CancellationException("screen closed") },
            )
        }
    }
}

// Invalid UTF-8, an embedded zero, and trailing whitespace-valued bytes must all survive.
private fun binaryKeyBytes() = byteArrayOf(0x98.toByte(), 0xff.toByte(), 0x00, 0xc3.toByte(), 0x28, 0x20, 0x0a)

private suspend fun importBinaryFile(
    content: ByteArray,
    readBytes: suspend (String) -> ByteArray = { content },
    importGpgKey: suspend (GpgKeyImportRequest) -> GpgKeyImportResult = {
        GpgKeyImportResult.NeedsPassphrase("OpenPGP")
    },
    onSuccess: suspend (GeneratedGpgKey) -> Unit = {
        fail("Unexpected success")
    },
    onNeedsPassphrase: suspend (GpgKeyImportResult.NeedsPassphrase, String?, ByteArray) -> Unit = { _, _, _ ->
        fail("Unexpected passphrase request")
    },
    onReadError: suspend () -> Unit = {
        fail("Unexpected read error")
    },
) = handleGpgKeyFileImport(
    info = FilePickerResult(
        uri = leParseUri("content://gpg/key"),
        name = "private.gpg",
        size = content.size.toLong(),
    ),
    readBytes = readBytes,
    importGpgKey = importGpgKey,
    onSuccess = onSuccess,
    onNeedsPassphrase = onNeedsPassphrase,
    onImportError = { fail("Unexpected import error") },
    onReadError = onReadError,
)
