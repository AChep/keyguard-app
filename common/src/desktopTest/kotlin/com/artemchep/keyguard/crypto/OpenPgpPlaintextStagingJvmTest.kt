package com.artemchep.keyguard.crypto

import com.artemchep.keyguard.common.service.staging.SpoolLimits
import com.artemchep.keyguard.common.service.staging.StagingPurpose
import com.artemchep.keyguard.common.service.staging.StagingSpoolFactory
import com.artemchep.keyguard.util.io.spool.AdaptiveSpool
import com.artemchep.keyguard.util.io.spool.ByteStoreWriter
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenPgpPlaintextStagingJvmTest {
    @Test
    fun provisionalPlaintextIsDiscardedWhenFinalizationFails() {
        val output = Buffer()
        val provisional = "unauthenticated plaintext".encodeToByteArray()

        val failure = assertFailsWith<AuthenticationFailure> {
            withStagedOpenPgpPlaintext(output) { staging ->
                staging.write(provisional)
                throw AuthenticationFailure()
            }
        }

        assertEquals("authentication failed", failure.message)
        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun plaintextIsPublishedOnlyAfterSuccessfulFinalization() {
        val output = Buffer()
        val plaintext = "authenticated plaintext".encodeToByteArray()

        val result = withStagedOpenPgpPlaintext(output) { staging ->
            staging.write(plaintext)
            "finished"
        }

        assertEquals("finished", result)
        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun plaintextSpillsToEncryptedStorageAfterMemoryLimit() {
        val output = Buffer()
        val plaintext = ByteArray(2 * 64 * 1024 + 31) { index ->
            (index % 251).toByte()
        }

        withStagedOpenPgpPlaintext(
            output = output,
            memoryLimitBytes = 4L,
        ) { staging ->
            staging.write(plaintext)
        }

        assertContentEquals(plaintext, output.readByteArray())
    }

    @Test
    fun spilledBytesDoNotContainPlaintext() {
        val storage = TestPrivateTemporaryStorage()
        val plaintext = "highly recognizable OpenPGP plaintext".encodeToByteArray()

        withStagedOpenPgpPlaintext(
            output = Buffer(),
            maxPlaintextBytes = 1024L,
            memoryLimitBytes = 0L,
            stagingSpoolFactory = stagingFactory(storage),
        ) { staging ->
            staging.write(plaintext)
        }

        assertFalse(storage.storedBytes().containsSubsequence(plaintext))
    }

    @Test
    fun corruptedEncryptedSpillIsRejectedBeforePublication() {
        val storage = TestPrivateTemporaryStorage(
            tamperOnFirstSource = { bytes ->
                bytes.apply {
                    if (isNotEmpty()) this[0] = (this[0].toInt() xor 1).toByte()
                }
            },
        )
        val output = Buffer()

        assertFailsWith<kotlinx.io.IOException> {
            withStagedOpenPgpPlaintext(
                output = output,
                maxPlaintextBytes = 1024L,
                memoryLimitBytes = 0L,
                stagingSpoolFactory = stagingFactory(storage),
            ) { staging ->
                staging.write("authenticated plaintext".encodeToByteArray())
            }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    @Test
    fun oversizedPlaintextIsDiscarded() {
        val output = Buffer()

        assertFailsWith<kotlinx.io.IOException> {
            withStagedOpenPgpPlaintext(
                output = output,
                maxPlaintextBytes = 4L,
            ) { staging ->
                staging.write(byteArrayOf(1, 2, 3, 4, 5))
            }
        }

        assertContentEquals(byteArrayOf(), output.readByteArray())
    }

    private class AuthenticationFailure : RuntimeException("authentication failed")
}

private fun stagingFactory(
    storage: TestPrivateTemporaryStorage,
) = object : StagingSpoolFactory {
    override fun create(
        purpose: StagingPurpose,
        limits: SpoolLimits,
        limitExceeded: (maximumBytes: Long) -> Throwable,
    ): ByteStoreWriter {
        assertEquals(StagingPurpose.OpenPgpPlaintext, purpose)
        return AdaptiveSpool(
            memoryLimitBytes = limits.memoryBytes,
            maximumBytes = limits.maximumBytes,
            spillFactory = {
                EncryptedTemporarySpillStorage.create(storage)
            },
            limitExceeded = limitExceeded,
        )
    }
}

private fun ByteArray.containsSubsequence(candidate: ByteArray): Boolean {
    if (candidate.isEmpty()) return true
    if (candidate.size > size) return false
    return (0..size - candidate.size).any { offset ->
        candidate.indices.all { index -> this[offset + index] == candidate[index] }
    }
}
