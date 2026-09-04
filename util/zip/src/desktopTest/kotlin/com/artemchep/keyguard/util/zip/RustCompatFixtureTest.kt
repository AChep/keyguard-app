package com.artemchep.keyguard.util.zip

import kotlinx.io.readByteArray
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lingala.zip4j.ZipFile as Zip4jFile

/**
 * Reads the golden archives written by the Rust writer, so JVM builds keep
 * reading archives from another Keyguard install.
 *
 * The fixtures in `src/desktopTest/resources/compat/rust` are produced by:
 *
 * ```text
 * cd util/zip/rust
 * cargo test -p keyguard-zip-core -- --ignored write_jvm_compat_fixtures
 * ```
 */
class RustCompatFixtureTest {
    @Test
    fun theFixturesAreTheOnesThatWereCommitted() {
        assertEquals(PLAIN_SHA256, sha256(fixture(PLAIN_FIXTURE)), PLAIN_FIXTURE)
        assertEquals(AES_SHA256, sha256(fixture(AES_FIXTURE)), AES_FIXTURE)
    }

    @Test
    fun theJdkReadsThePlainFixture() {
        val names = mutableListOf<String>()
        val contents = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(fixture(PLAIN_FIXTURE))).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break
                names += entry.name
                contents += zipStream.readBytes()
            }
        }

        assertEquals(CompatFixtures.NAMES, names)
        CompatFixtures.CONTENTS.forEachIndexed { index, expected ->
            assertContentEquals(expected, contents[index], CompatFixtures.NAMES[index])
        }
    }

    @Test
    fun theModuleReaderReadsThePlainFixture() {
        ZipReader(fixture(PLAIN_FIXTURE).source()).use { reader ->
            assertEntries(reader)
        }
    }

    @Test
    fun theModuleReaderDecryptsTheEncryptedFixture() {
        ZipReader(fixture(AES_FIXTURE).source(), CompatFixtures.PASSWORD).use { reader ->
            assertEntries(reader)
        }
    }

    @Test
    fun theEncryptedFixtureRejectsAWrongPassword() {
        assertFails {
            ZipReader(fixture(AES_FIXTURE).source(), "wrong password").use { reader ->
                while (true) {
                    val entry = reader.nextEntry() ?: break
                    entry.source.readByteArray()
                }
            }
        }
    }

    @Test
    fun zip4jSeesEveryEntryOfTheEncryptedFixtureAsAes() {
        val path = Files.createTempFile("keyguard-zip-compat", ".zip")
        try {
            path.writeBytes(fixture(AES_FIXTURE))

            val headers = Zip4jFile(path.toFile(), CompatFixtures.PASSWORD.toCharArray()).fileHeaders
            assertEquals(CompatFixtures.NAMES, headers.map { it.fileName })
            headers.forEach { header ->
                assertTrue(header.isEncrypted, "${header.fileName} is encrypted")
                assertEquals(
                    EncryptionMethod.AES,
                    header.encryptionMethod,
                    header.fileName,
                )
            }
        } finally {
            path.deleteIfExists()
        }
    }

    private fun assertEntries(reader: ZipReader) {
        CompatFixtures.NAMES.forEachIndexed { index, name ->
            val entry = reader.nextEntry()
            assertEquals(name, entry?.name)
            assertContentEquals(CompatFixtures.CONTENTS[index], entry?.source?.readByteArray(), name)
        }
        assertNull(reader.nextEntry(), "the archive is exhausted")
    }
}

private const val PLAIN_FIXTURE = "/compat/rust/plain.zip"

private const val AES_FIXTURE = "/compat/rust/aes256.zip"

/**
 * AES entries carry a random salt, so regenerating the fixtures changes these
 * digests; update them in the same change.
 */
private const val PLAIN_SHA256 =
    "5c0bf4724576d99d737b1239fc3fc9a7c2e10a08e46f052a32ae33720b01a3b9"

private const val AES_SHA256 =
    "ff4cf12503bf42889e38cf37bcf59eda5e53fd11b04ddd662514fd637d2a5478"

private fun fixture(resource: String): ByteArray = requireNotNull(
    RustCompatFixtureTest::class.java.getResourceAsStream(resource),
) { "The fixture $resource must be on the test classpath" }
    .use { it.readBytes() }

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .toHexString()
