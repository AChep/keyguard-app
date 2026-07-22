package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseWriteMode
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOperation
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DbUtilsReadRetryTest {
    @Test
    fun `remote storage retries complete read after malformed database content`() = runTest {
        val storage = TestStorage(decodeReadAttempts = 2)
        var decodeAttempts = 0

        val result = storage.readWithDecodeRetry {
            decodeAttempts += 1
            if (decodeAttempts == 1) {
                throw FormatError.InvalidXml("truncated XML")
            }
            "decoded"
        }

        assertEquals("decoded", result)
        assertEquals(2, storage.reads)
    }

    @Test
    fun `unsupported database version is not retried`() = runTest {
        val storage = TestStorage(decodeReadAttempts = 2)

        assertFailsWith<FormatError.UnsupportedVersion> {
            storage.readWithDecodeRetry {
                throw FormatError.UnsupportedVersion("unsupported")
            }
        }

        assertEquals(1, storage.reads)
    }

    @Test
    fun `invalid database key is not retried`() = runTest {
        val storage = TestStorage(decodeReadAttempts = 2)

        assertFailsWith<CryptoError.InvalidKey> {
            storage.readWithDecodeRetry {
                throw CryptoError.InvalidKey("wrong key")
            }
        }

        assertEquals(1, storage.reads)
    }

    @Test
    fun `remote storage reopens source after midstream transport failure`() = runTest {
        val expected = "complete database".encodeToByteArray()
        val storage = TestStorage(decodeReadAttempts = 2) { read ->
            if (read == 1) {
                failingSourceAfter("partial".encodeToByteArray())
            } else {
                Buffer().apply { write(expected) }
            }
        }

        val actual = storage.readWithDecodeRetry { source ->
            source.readByteArray()
        }

        assertEquals(expected.decodeToString(), actual.decodeToString())
        assertEquals(2, storage.reads)
    }
}

private class TestStorage(
    override val decodeReadAttempts: Int,
    private val sourceFactory: (read: Int) -> Source = { Buffer() },
) : KeePassDatabaseStorage {
    var reads: Int = 0

    override suspend fun exists(): Boolean = true

    override suspend fun stat(): KeePassDatabaseMetadata? = null

    override suspend fun read(): Source {
        reads += 1
        return sourceFactory(reads)
    }

    override suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata?,
    ): KeePassDatabaseMetadata? = error("Not used by this test")
}

private fun failingSourceAfter(prefix: ByteArray): Source = object : RawSource {
    private var emittedPrefix = false

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (!emittedPrefix) {
            emittedPrefix = true
            val count = minOf(byteCount, prefix.size.toLong())
            sink.write(prefix, startIndex = 0, endIndex = count.toInt())
            return count
        }
        throw WebDavException.Transient(
            operation = WebDavOperation.Read,
            path = "database.kdbx",
            cause = IllegalStateException("connection closed"),
        )
    }

    override fun close() = Unit
}.buffered()
