package com.artemchep.keyguard.crypto.staging

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolBacking
import com.artemchep.keyguard.common.service.staging.StagingSpoolEvent
import com.artemchep.keyguard.common.service.staging.StagingSpoolObserver
import com.artemchep.keyguard.common.service.staging.StagingSpoolOutcome
import com.artemchep.keyguard.crypto.TestPrivateTemporaryStorage
import com.artemchep.keyguard.crypto.readBytes
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(InternalKeyguardIoApi::class)
class DefaultStagingSpoolFactoryTest {
    @Test
    fun plaintextPurposesEncryptSpilledBytes() {
        val plaintext = "recognizable provisional plaintext".encodeToByteArray()
        val purposes = listOf(
            StagingPurpose.DownloadSinkPlaintext,
            StagingPurpose.PendingUploadPlaintext,
            StagingPurpose.OpenPgpPlaintext,
            StagingPurpose.KeePassAttachmentPlaintext,
        )

        purposes.forEach { purpose ->
            val storage = TestPrivateTemporaryStorage()
            val factory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { storage },
            )
            val snapshot = factory.create(
                purpose = purpose,
                limits = SpoolLimits(
                    memoryBytes = 0L,
                    maximumBytes = plaintext.size.toLong(),
                ),
                limitExceeded = ::testLimitExceeded,
            ).use { writer ->
                writer.sink().use { sink -> sink.write(plaintext) }
                writer.seal()
            }

            snapshot.use {
                assertContentEquals(plaintext, snapshot.readBytes())
                assertFalse(
                    storage.storedBytes().containsSubsequence(plaintext),
                    "$purpose spilled plaintext without encryption",
                )
            }
        }
    }

    @Test
    fun ciphertextAndKeePassPurposesUseRawPrivateScratch() {
        val payload = "recognizable non-transient payload".encodeToByteArray()
        val purposes = listOf(
            StagingPurpose.FileCiphertext,
            StagingPurpose.KeePassDatabase,
        )

        purposes.forEach { purpose ->
            val storage = TestPrivateTemporaryStorage()
            val factory = DefaultStagingSpoolFactory.forTesting(
                scratchStorageFactory = { storage },
            )
            val snapshot = factory.create(
                purpose = purpose,
                limits = SpoolLimits(
                    memoryBytes = 0L,
                    maximumBytes = payload.size.toLong(),
                ),
                limitExceeded = ::testLimitExceeded,
            ).use { writer ->
                writer.sink().use { sink -> sink.write(payload) }
                writer.seal()
            }

            snapshot.use {
                assertContentEquals(payload, snapshot.readBytes())
                assertContentEquals(payload, storage.storedBytes())
            }
        }
    }

    @Test
    fun confidentialPurposeFailsClosedWhenEncryptedSpillCannotBeCreated() {
        val failure = IOException("scratch unavailable")
        val factory = DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = {
                throw failure
            },
        )

        val actual = assertFailsWith<IOException> {
            factory.create(
                purpose = StagingPurpose.DownloadSinkPlaintext,
                limits = SpoolLimits(
                    memoryBytes = 0L,
                    maximumBytes = 1L,
                ),
                limitExceeded = ::testLimitExceeded,
            ).use { writer ->
                writer.sink().use { sink -> sink.write(byteArrayOf(1)) }
            }
        }

        assertSame(failure, actual)
    }

    @Test
    fun limitsRejectInvalidRanges() {
        assertFailsWith<IllegalArgumentException> {
            SpoolLimits(
                memoryBytes = -1L,
                maximumBytes = 1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpoolLimits(
                memoryBytes = 2L,
                maximumBytes = 1L,
            )
        }
    }

    @Test
    fun observerReceivesOnlyCoarseLifecycleData() {
        val events = mutableListOf<StagingSpoolEvent>()
        val storage = TestPrivateTemporaryStorage()
        val factory = DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = { storage },
            observer = StagingSpoolObserver(events::add),
        )

        factory.create(
            purpose = StagingPurpose.OpenPgpPlaintext,
            limits = SpoolLimits(
                memoryBytes = 0L,
                maximumBytes = 1L,
            ),
            limitExceeded = ::testLimitExceeded,
        ).use { writer ->
            writer.sink().use { sink -> sink.write(byteArrayOf(1)) }
            writer.seal().close()
        }

        assertEquals(
            listOf(
                StagingSpoolEvent(
                    purpose = StagingPurpose.OpenPgpPlaintext,
                    outcome = StagingSpoolOutcome.Sealed,
                    backing = StagingSpoolBacking.Spill,
                ),
            ),
            events,
        )
    }

    @Test
    fun observerFailureCannotBreakStaging() {
        val factory = DefaultStagingSpoolFactory.forTesting(
            scratchStorageFactory = {
                error("A memory-only spool must not create scratch storage")
            },
            observer = StagingSpoolObserver {
                error("observer unavailable")
            },
        )

        val snapshot = factory.create(
            purpose = StagingPurpose.OpenPgpPlaintext,
            limits = SpoolLimits(
                memoryBytes = 1L,
                maximumBytes = 1L,
            ),
            limitExceeded = ::testLimitExceeded,
        ).use { writer ->
            writer.sink().use { sink -> sink.write(byteArrayOf(1)) }
            writer.seal()
        }

        snapshot.use {
            assertContentEquals(byteArrayOf(1), snapshot.readBytes())
        }
    }
}

private fun testLimitExceeded(
    maximumBytes: Long,
) = IOException("Test staging limit exceeded: $maximumBytes")

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean =
    when {
        candidate.isEmpty() -> true

        candidate.size > size -> false

        else -> (0..size - candidate.size).any { offset ->
            candidate.indices.all { index -> this[offset + index] == candidate[index] }
        }
    }
