package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.encodeTo
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.errors.CryptoError
import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseWriteMode
import com.artemchep.keyguard.util.webdav.WebDavException
import com.artemchep.keyguard.util.webdav.WebDavOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.write
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

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

    @Test
    fun `non-retryable WebDAV failure inside decoder is preserved`() = runTest {
        val credentials = Credentials.from(EncryptedValue.fromString("password"))
        val encoded = encodedDatabase(credentials)
        val expected = WebDavException.AuthenticationFailed(
            operation = WebDavOperation.Read,
        )
        val storage = TestStorage(decodeReadAttempts = 2) { read ->
            if (read == 1) {
                failingSourceAfter(encoded.copyOf(64), expected)
            } else {
                Buffer().apply { write(encoded) }
            }
        }

        val actual = assertFailsWith<WebDavException.AuthenticationFailed> {
            storage.readWithDecodeRetry { source ->
                KeePassDatabase.decode(source, credentials)
            }
        }

        assertSame(expected, actual)
        assertEquals(1, storage.reads)
    }

    @Test
    fun `cancellation inside decoder is preserved without retry`() = runTest {
        val credentials = Credentials.from(EncryptedValue.fromString("password"))
        val encoded = encodedDatabase(credentials)
        val expected = CancellationException("cancelled")
        val storage = TestStorage(decodeReadAttempts = 2) {
            failingSourceAfter(encoded.copyOf(64), expected)
        }

        val actual = assertFailsWith<CancellationException> {
            storage.readWithDecodeRetry { source ->
                KeePassDatabase.decode(source, credentials)
            }
        }

        assertSame(expected, actual)
        assertEquals(1, storage.reads)
    }

    @Test
    fun `transient WebDAV failure inside decoder retries from a fresh source`() = runTest {
        val credentials = Credentials.from(EncryptedValue.fromString("password"))
        val encoded = encodedDatabase(credentials)
        val expected = WebDavException.Transient(
            operation = WebDavOperation.Read,
            path = "database.kdbx",
            cause = IllegalStateException("connection closed"),
        )
        val storage = TestStorage(decodeReadAttempts = 2) { read ->
            if (read == 1) {
                failingSourceAfter(encoded.copyOf(64), expected)
            } else {
                Buffer().apply { write(encoded) }
            }
        }

        val database = storage.readWithDecodeRetry { source ->
            KeePassDatabase.decode(source, credentials)
        }

        assertEquals("Retry database", database.content.meta.name)
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

private fun encodedDatabase(credentials: Credentials): ByteArray {
    val database =
        KeePassDatabase.Ver4x
            .create(
                rootName = "Root",
                meta = Meta(name = "Retry database"),
                credentials = credentials,
            ).let { database ->
                database.copy(
                    header = database.header.copy(
                        kdfParameters = KdfParameters.Aes(
                            rounds = 1U,
                            seed = ByteArray(32) { it.toByte() }.toByteString(),
                        ),
                    ),
                )
            }
    return Buffer()
        .also(database::encodeTo)
        .readByteArray()
}

private fun failingSourceAfter(
    prefix: ByteArray,
    failure: Throwable = WebDavException.Transient(
        operation = WebDavOperation.Read,
        path = "database.kdbx",
        cause = IllegalStateException("connection closed"),
    ),
): Source = object : RawSource {
    private var offset = 0

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount == 0L) return 0L
        if (offset < prefix.size) {
            val count = minOf(byteCount, (prefix.size - offset).toLong()).toInt()
            sink.write(prefix, startIndex = offset, endIndex = offset + count)
            offset += count
            return count.toLong()
        }
        throw failure
    }

    override fun close() = Unit
}.buffered()
