package com.artemchep.keyguard.common.service.keepass

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.Meta
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseMetadata
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseStorage
import com.artemchep.keyguard.common.service.keepass.storage.KeePassDatabaseWriteMode
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.crypto.TestPrivateTemporaryStorage
import com.artemchep.keyguard.crypto.staging.DefaultStagingSpoolFactory
import com.artemchep.keyguard.util.foundation.crypto.sha256
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(InternalKeyguardIoApi::class)
class KeePassAttachmentExtractorTest {
    private val credentials = Credentials.from(
        EncryptedValue.fromString("extractor-test-password"),
    )

    @Test
    fun largeMatchSpillsEncryptedAndIsReleasedByCaller() {
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 64 * 1024) {
            0x5a
        }
        val encoded = databaseWith(attachment).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        val staged = extractor.extract(
            source = Buffer().apply { write(encoded) },
            credentials = credentials,
            contentHash = sha256(attachment),
            expectedSize = attachment.size.toLong(),
        )

        assertEquals(attachment.size.toLong(), staged.size)
        staged.source().use { source ->
            assertContentEquals(attachment, source.readByteArray())
        }
        assertEquals(0, scratch.closeCount)
        assertFalse(
            scratch.storedBytes().containsRun(
                value = 0x5a,
                length = 4 * 1024,
            ),
            "KeePass plaintext was visible in spilled scratch bytes.",
        )

        staged.close()

        assertEquals(1, scratch.closeCount)
    }

    @Test
    fun missingMatchReleasesCandidateSnapshot() {
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 1) { 7 }
        val encoded = databaseWith(attachment).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        assertFails {
            extractor.extract(
                source = Buffer().apply { write(encoded) },
                credentials = credentials,
                contentHash = ByteArray(32) { 9 },
                expectedSize = attachment.size.toLong(),
            )
        }

        assertEquals(1, scratch.closeCount)
    }

    @Test
    fun matchingHashWithWrongExpectedSizeReleasesCandidate() {
        // KDBX 3, so the candidate carries no declared length and is fully
        // staged before the size mismatch is discovered.
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 1) { 11 }
        val encoded = databaseWith(attachment, majorVersion = 3).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        assertFails {
            extractor.extract(
                source = Buffer().apply { write(encoded) },
                credentials = credentials,
                contentHash = sha256(attachment),
                expectedSize = attachment.size.toLong() + 1L,
            )
        }

        assertEquals(1, scratch.closeCount)
    }

    @Test
    fun candidateLargerThanExpectedSizeIsSkippedWithoutFullStaging() {
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 1) { 13 }
        val encoded = databaseWith(attachment).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        assertFails {
            extractor.extract(
                source = Buffer().apply { write(encoded) },
                credentials = credentials,
                contentHash = sha256(attachment),
                expectedSize = attachment.size.toLong() - 1L,
            )
        }

        // The KDBX 4 inner header declares the binary's size upfront, so the
        // wrong-size candidate is skipped without ever being staged.
        assertEquals(0, scratch.closeCount)
        assertEquals(0, scratch.storedBytes().size)
    }

    @Test
    fun kdbx3CandidateLargerThanExpectedSizeStopsStagingBeforeSpilling() {
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 1) { 17 }
        val encoded = databaseWith(attachment, majorVersion = 3).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        assertFails {
            extractor.extract(
                source = Buffer().apply { write(encoded) },
                credentials = credentials,
                contentHash = sha256(attachment),
                expectedSize = attachment.size.toLong() - 1L,
            )
        }

        // KDBX 3 binaries are XML-embedded and carry no declared length, so
        // the candidate is staged until the streaming cutoff fires — which
        // happens before the spool outgrows its memory threshold.
        assertEquals(0, scratch.closeCount)
        assertEquals(0, scratch.storedBytes().size)
    }

    @Test
    fun kdbx4WrongSizeDecoyIsDrainedWithoutStaging() {
        val decoy = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 64 * 1024) { 21 }
        val target = "target attachment".encodeToByteArray()
        val encoded = databaseWith(decoy, target).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        val staged = extractor.extract(
            source = Buffer().apply { write(encoded) },
            credentials = credentials,
            contentHash = sha256(target),
            expectedSize = target.size.toLong(),
        )

        staged.use {
            assertEquals(target.size.toLong(), it.size)
            it.source().use { source ->
                assertContentEquals(target, source.readByteArray())
            }
        }
        // The oversized decoy precedes the target but declares a wrong size,
        // so it is drained without staging; the small target stays in memory.
        assertEquals(0, scratch.closeCount)
        assertEquals(0, scratch.storedBytes().size)
    }

    @Test
    fun cancellationReleasesSpilledCandidate() {
        val attachment = ByteArray(KEEPASS_ATTACHMENT_MEMORY_BYTES.toInt() + 64 * 1024) { 3 }
        val encoded = databaseWith(attachment).encode()
        val scratch = TestPrivateTemporaryStorage()
        val extractor = KeePassAttachmentExtractor(
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { scratch },
            ),
        )

        assertFailsWith<CancellationException> {
            extractor.extract(
                source = Buffer().apply { write(encoded) },
                credentials = credentials,
                contentHash = sha256(attachment),
                expectedSize = attachment.size.toLong(),
                checkCancellation = {
                    if (scratch.storedBytes().isNotEmpty()) {
                        throw CancellationException("cancel attachment extraction")
                    }
                },
            )
        }

        assertEquals(1, scratch.closeCount)
    }

    @Test
    fun readerReopensAndClosesStorageSourceAfterRetryableDecodeFailure() = runTest {
        val attachment = "reader retry attachment".encodeToByteArray()
        val encoded = databaseWith(attachment).encode()
        val storage = RetryingAttachmentStorage(
            payloads = listOf(
                encoded.copyOf(encoded.size / 2),
                encoded,
            ),
        )
        val token = KeePassToken(
            id = "account",
            key = KeePassToken.Key(
                passwordBase64 = EncryptedValue
                    .fromString("extractor-test-password")
                    .toBase64(),
            ),
            database = KeePassToken.Database(
                fileName = "vault.kdbx",
                location = FileLocation.Local(
                    uri = "file:///vault.kdbx",
                    accessToken = null,
                    managedByApp = false,
                    displayName = "vault.kdbx",
                ),
            ),
        )
        val reader = KeePassAttachmentReader(
            base64Service = Base64ServiceImpl(),
            storageFactory = KeePassAttachmentStorageFactory { requestedToken ->
                assertEquals(token, requestedToken)
                storage
            },
            stagingSpoolFactory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = ::TestPrivateTemporaryStorage,
            ),
        )

        reader.read(
            token = token,
            contentHash = sha256(attachment),
            expectedSize = attachment.size.toLong(),
        ).use { staged ->
            staged.source().use { source ->
                assertContentEquals(attachment, source.readByteArray())
            }
        }

        assertEquals(2, storage.reads)
        assertEquals(2, storage.closes)
    }

    private fun databaseWith(
        vararg attachments: ByteArray,
        majorVersion: Int = 4,
    ): KeePassDatabase {
        val database = when (majorVersion) {
            3 -> KeePassDatabase.Ver3x.create(
                rootName = "Root",
                meta = Meta(name = "Attachment extractor test"),
                credentials = credentials,
            ).let { database ->
                database.copy(
                    header = database.header.copy(
                        compression = DatabaseHeader.Compression.None,
                        transformRounds = 1U,
                    ),
                )
            }

            4 -> KeePassDatabase.Ver4x.create(
                rootName = "Root",
                meta = Meta(name = "Attachment extractor test"),
                credentials = credentials,
            ).let { database ->
                database.copy(
                    header = database.header.copy(
                        compression = DatabaseHeader.Compression.None,
                        kdfParameters = KdfParameters.Aes(
                            rounds = 1U,
                            seed = ByteArray(32) { index -> index.toByte() }.toByteString(),
                        ),
                    ),
                )
            }

            else -> error("Unsupported test version.")
        }
        return database.modifyBinaries {
            attachments
                .map { attachment ->
                    BinaryData.Uncompressed(
                        memoryProtection = false,
                        rawContent = attachment,
                    )
                }
                .associateByTo(linkedMapOf()) { binary -> binary.hash }
        }
    }
}

private class RetryingAttachmentStorage(
    private val payloads: List<ByteArray>,
) : KeePassDatabaseStorage {
    override val decodeReadAttempts: Int = payloads.size

    var reads = 0
    var closes = 0

    override suspend fun exists(): Boolean = true

    override suspend fun stat(): KeePassDatabaseMetadata? = null

    override suspend fun read(): Source {
        val payload = payloads[reads++]
        val buffer = Buffer().apply { write(payload) }
        return object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = buffer.readAtMostTo(sink, byteCount)

            override fun close() {
                closes += 1
            }
        }.buffered()
    }

    override suspend fun publish(
        mode: KeePassDatabaseWriteMode,
        staged: StagedDatabase,
        expected: KeePassDatabaseMetadata?,
    ): KeePassDatabaseMetadata? = error("Not used by this test")
}

private fun ByteArray.containsRun(
    value: Byte,
    length: Int,
): Boolean {
    var current = 0
    for (byte in this) {
        current = if (byte == value) current + 1 else 0
        if (current >= length) return true
    }
    return false
}
