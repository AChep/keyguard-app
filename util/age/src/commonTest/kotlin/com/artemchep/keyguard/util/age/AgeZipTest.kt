package com.artemchep.keyguard.util.age

import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class AgeZipTest {
    @Test
    fun `streams multiple entries and preserves unicode passwords`() = runTest {
        val secret = "  пароль 🔑  "
        val data = ByteArray(256 * 1024) { (it * 37).toByte() }
        val encrypted = Buffer()
        AgeZipWriter(encrypted, AgePassword(secret)).use { writer ->
            writer.entry("vault.json") { it.writeString("{\"items\":[]}") }
            writer.entry("attachments/файл.bin") { it.write(data) }
            writer.entry("empty") { }
            writer.finish()
        }
        val bytes = encrypted.readByteArray()
        assertTrue(bytes.decodeToString().startsWith("age-encryption.org/v1\n"))
        AgeZipReader(Buffer().apply { write(bytes) }, AgePassword(secret)).use { reader ->
            val first = reader.nextEntry()!!
            assertEquals("vault.json", first.name)
            assertEquals("{\"items\":[]}", first.source.readString())
            val attachment = reader.nextEntry()!!
            assertEquals("attachments/файл.bin", attachment.name)
            assertContentEquals(data, attachment.source.readByteArray())
            assertEquals(0L, reader.nextEntry()!!.source.readByteArray().size.toLong())
            assertNull(reader.nextEntry())
        }
    }

    @Test
    fun `identities open only their own archives without a password`() = runTest {
        val identity = AgeIdentity.generate()
        assertTrue(identity.value.startsWith("AGE-SECRET-KEY-1"))
        assertFalse(identity.value in identity.toString())
        val encrypted = Buffer()
        AgeZipWriter(encrypted, identity).use { writer ->
            writer.entry("index.json") { it.writeString("{}") }
            writer.finish()
        }
        val bytes = encrypted.readByteArray()
        AgeZipReader(Buffer().apply { write(bytes) }, identity).use { reader ->
            val entry = assertNotNull(reader.nextEntry())
            assertEquals("index.json", entry.name)
            assertEquals("{}", entry.source.readString())
        }
        for (other in listOf(AgeIdentity.generate(), AgePassword(identity.value))) {
            val error = assertFailsWith<AgeException> {
                AgeZipReader(Buffer().apply { write(bytes) }, other).close()
            }
            assertTrue(error.reason in setOf(AgeFailure.Password, AgeFailure.Corrupt), "$other: ${error.reason}")
        }
    }

    @Test
    fun `authenticates unselected tail before returning entries`() = runTest {
        val output = Buffer()
        AgeZipWriter(output, AgePassword("password")).use { writer ->
            writer.entry("vault.json") { it.writeString("secret") }
            writer.finish()
        }
        val bytes = output.readByteArray()
        val wrong = assertFailsWith<AgeException> {
            AgeZipReader(Buffer().apply { write(bytes) }, AgePassword("wrong")).close()
        }
        assertEquals(AgeFailure.Password, wrong.reason)
        for (broken in listOf(bytes.copyOf(bytes.size - 1), bytes.copyOf().apply {
            this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte()
        })) {
            assertEquals(AgeFailure.Corrupt, assertFailsWith<AgeException> {
                AgeZipReader(Buffer().apply { write(broken) }, AgePassword("password")).close()
            }.reason)
        }
    }

    @Test
    fun `spool leaves no named file behind`() = runTest {
        val output = Buffer()
        AgeZipWriter(output, AgePassword("password")).use { writer ->
            writer.entry("vault.json") { it.writeString("secret") }
            writer.finish()
        }
        val bytes = output.readByteArray()
        val directory = Path(SystemTemporaryDirectory, "keyguard-age-spool-${Uuid.random()}")
        SystemFileSystem.createDirectories(directory)
        try {
            val directoryName = directory.toString()
            AgeZipReader(Buffer().apply { write(bytes) }, AgePassword("password"), directoryName).use { reader ->
                // POSIX unlinks the spool at creation; Windows keeps a reserved,
                // share-nothing name until the session closes.
                assertTrue(SystemFileSystem.list(directory).all { it.name.startsWith(".kg-tmp-v1u-s-") })
                assertEquals("secret", reader.nextEntry()!!.source.readString())
            }
            assertFailsWith<AgeException> {
                AgeZipReader(Buffer().apply { write(bytes) }, AgePassword("wrong"), directoryName).close()
            }
            assertTrue(SystemFileSystem.list(directory).isEmpty())
        } finally {
            SystemFileSystem.list(directory).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(directory)
        }
    }

    @Test
    fun `entry and encrypted input limits are enforced`() = runTest {
        val output = Buffer()
        AgeZipWriter(output, AgePassword("password")).use { writer ->
            writer.entry("vault.json") { it.writeString("more than four bytes") }
            writer.finish()
        }
        val bytes = output.readByteArray()
        assertEquals(AgeFailure.Limit, assertFailsWith<AgeException> {
            AgeZipReader(Buffer().apply { write(bytes) }, AgePassword("password"), maxEncryptedBytes = 4).close()
        }.reason)
        AgeZipReader(Buffer().apply { write(bytes) }, AgePassword("password"), maxEntryBytes = 4).use { reader ->
            assertEquals(AgeFailure.Limit, assertFailsWith<AgeException> {
                reader.nextEntry()!!.source.readString()
            }.reason)
        }
    }

    @Test
    fun `invalid passwords do not exhaust the native handle registry`() {
        repeat(20) {
            assertFails { AgeZipWriter(Buffer(), AgePassword("\uD800")) }
        }
        AgeZipWriter(Buffer(), AgePassword("password")).use { it.finish() }
    }

    @Test
    fun `abandoned export never becomes a readable archive`() = runTest {
        val output = Buffer()
        AgeZipWriter(output, AgePassword("password")).use { writer ->
            writer.entry("vault.json") { it.writeString("secret") }
        }
        assertFailsWith<AgeException> { AgeZipReader(output, AgePassword("password")).close() }
    }
}
