package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.service.crypto.GpgTestKeyFixtures
import com.artemchep.keyguard.common.service.crypto.publicKeyArmoredOf
import com.artemchep.keyguard.crypto.NativeGpgPublicKeyParser
import com.artemchep.keyguard.feature.home.vault.model.VaultViewItem
import com.artemchep.keyguard.test.testCopyText
import kotlinx.coroutines.test.runTest
import org.bouncycastle.bcpg.ArmoredInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the GPG key preview against the native OpenPGP
 * parser, with real keys exported by GnuPG.
 */
class AttachmentPreviewGpgKeyDecoderJvmTest {
    private val decoder = testGpgKeyDecoder(parser = NativeGpgPublicKeyParser)

    @Test
    fun `armored public key`() = runTest {
        val armored = publicKeyArmoredOf(GpgTestKeyFixtures.CV25519)

        val content = decoder.decode("key.asc", armored.encodeToByteArray(), testCopyText())

        val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
        assertNotNull(gpgKey.source)
        assertEquals("pub", gpgKey.secPub())
        gpgKey.assertCv25519Details()
    }

    @Test
    fun `binary public key`() = runTest {
        val binary = publicKeyArmoredOf(GpgTestKeyFixtures.CV25519).dearmor()

        val content = decoder.decode("key.gpg", binary, testCopyText())

        val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
        assertNull(gpgKey.source)
        assertEquals("pub", gpgKey.secPub())
        gpgKey.assertCv25519Details()
    }

    @Test
    fun `armored secret key`() = runTest {
        val armored = GpgTestKeyFixtures.CV25519

        val content = decoder.decode("key.asc", armored.encodeToByteArray(), testCopyText())

        val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
        assertEquals(armored, gpgKey.source?.text)
        assertEquals("sec", gpgKey.secPub())
        gpgKey.assertCv25519Details()
    }

    @Test
    fun `binary secret key`() = runTest {
        val binary = GpgTestKeyFixtures.CV25519.dearmor()

        val content = decoder.decode("key.pgp", binary, testCopyText())

        val gpgKey = assertIs<AttachmentPreviewContent.GpgKey>(content)
        assertNull(gpgKey.source)
        assertEquals("sec", gpgKey.secPub())
        gpgKey.assertCv25519Details()
    }

    @Test
    fun `binary data that is not a key is an error`() = runTest {
        val binary = byteArrayOf(0x85.toByte(), 0x01, 0x0C, 0x03)

        val content = decoder.decode("message.gpg", binary, testCopyText())

        assertEquals(AttachmentPreviewError.GpgKey, assertIs<AttachmentPreviewContent.Error>(content).type)
    }

    private fun AttachmentPreviewContent.GpgKey.assertCv25519Details() {
        val details = items
            .filterIsInstance<VaultViewItem.Table>()
            .first { it.id == "gpg.0.details" }
        assertTrue(
            details.rows.any { it.value == "D0BB CFBB 250D 3BB0 658E 5384 F83D 947D 29EF ECF7" },
            details.rows.toString(),
        )
        val ids = items.map { it.id }
        assertTrue("gpg.0.subKey.0" in ids, ids.toString())
        assertTrue(
            items.any { it is VaultViewItem.Value && "cv25519@test.invalid" in it.value },
            ids.toString(),
        )
    }
}

private fun String.dearmor(): ByteArray = ArmoredInputStream(byteInputStream()).use { it.readBytes() }
