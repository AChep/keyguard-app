package com.artemchep.keyguard.nativecrypto

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeCryptoOpenPgpLargeMdcTest {
    @Test
    fun largeLegacyFilesRoundtripThroughJniAndBoundedDrain() {
        val publicKey = fixture("mdc-public.asc")
        val privateKey = fixture("mdc-secret.asc")
        val directory = createTempDirectory("keyguard-mdc-test")
        val size = 17 * 1024 * 1024
        val input = ByteArray(64 * 1024) { (it % 251).toByte() }
        try {
            for (compression in listOf(false, true)) {
                val encrypted = ByteArrayOutputStream()
                NativeCrypto.openPgp.openEncryption(
                    publicKeys = listOf(publicKey),
                    candidateRevocationKeys = emptyList(),
                    fileName = "large-mdc.bin",
                    armored = false,
                    referenceTimeEpochSeconds = 1_800_000_000,
                    enableCompression = compression,
                ).use { session ->
                    repeat(size / input.size) {
                        session.update(input).also { encrypted.write(it); it.fill(0) }
                    }
                    val final = session.finish()
                    assertEquals(NativeOpenPgpProtectionMode.SEIPD_V1_MDC, final.protectionMode)
                    encrypted.write(final.data)
                    final.data.fill(0)
                }
                val ciphertext = encrypted.toByteArray()
                NativeCrypto.openPgp.openDecryption(
                    privateKeys = listOf(privateKey),
                    stagingDirectory = directory.toString(),
                ).use { session ->
                    var offset = 0
                    while (offset < ciphertext.size) {
                        val count = minOf(input.size, ciphertext.size - offset)
                        assertTrue(session.update(ciphertext, offset, count).isEmpty())
                        offset += count
                    }
                    val total = session.assertBoundedDrain(input)
                    val final = session.finish()
                    assertTrue(final.data.isEmpty())
                    assertEquals(size, total)
                    assertEquals(size.toLong(), final.metadata?.originalSize)
                }
                if (compression) {
                    val failure = assertFailsWith<NativeCryptoException> {
                        NativeCrypto.openPgp.decrypt(ciphertext, listOf(privateKey))
                    }
                    assertEquals(NativeCryptoErrorCode.RESOURCE_LIMIT, failure.code)
                }
                ciphertext.fill(0)
            }
            assertEquals(0, directory.toFile().listFiles()?.size)
        } finally {
            input.fill(0)
            privateKey.fill(0)
            publicKey.fill(0)
            directory.toFile().deleteRecursively()
        }
    }

    private fun NativeOpenPgpDecryptionSession.assertBoundedDrain(input: ByteArray): Int {
        var total = 0
        while (true) {
            val output = drain()
            if (output.isEmpty()) break
            try {
                assertTrue(output.size <= input.size)
                assertTrue(output.indices.all { output[it] == input[(total + it) % input.size] })
                total += output.size
            } finally {
                output.fill(0)
            }
        }
        return total
    }

    private fun fixture(name: String): ByteArray {
        val suffix = Path.of("util/crypto/rust/crates/keyguard-crypto-core/tests/fixtures/openpgp", name)
        return generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .map { it.resolve(suffix) }
            .first { it.isRegularFile() }
            .readBytes()
    }
}
