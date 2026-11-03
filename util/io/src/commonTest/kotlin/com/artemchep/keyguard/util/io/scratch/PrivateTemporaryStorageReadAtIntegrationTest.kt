package com.artemchep.keyguard.util.io.scratch

import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.spool.assertInvalidPositionalReads
import com.artemchep.keyguard.util.io.spool.assertPositionalRead
import com.artemchep.keyguard.util.io.spool.assertPositionalReadContract
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/** Exercises the real JNI or Apple C bridge, depending on the test target. */
@OptIn(InternalKeyguardIoApi::class)
class PrivateTemporaryStorageReadAtIntegrationTest {
    @Test
    fun realStorageObeysThePositionalReadContract() {
        for (size in listOf(0, 1, 600_019)) {
            val payload = Random(size).nextBytes(size)
            withSealedPrivateStorage(payload) { storage ->
                assertPositionalReadContract(payload, storage::readAt)
                assertInvalidPositionalReads(storage::readAt)
            }
        }
    }

    @Test
    fun sequentialAndPositionalReadsDoNotMoveEachOthersOffsets() {
        val payload = Random(91).nextBytes(600_019)
        withSealedPrivateStorage(payload) { storage ->
            storage.source().buffered().use { first ->
                storage.source().buffered().use { second ->
                    assertContentEquals(payload.copyOfRange(0, 17), first.readByteArray(17))
                    assertPositionalRead(payload, storage::readAt, payload.size - 3L)
                    assertContentEquals(payload.copyOfRange(0, 31), second.readByteArray(31))
                    assertPositionalRead(payload, storage::readAt, payload.size.toLong())
                    assertPositionalRead(payload, storage::readAt, 7)
                    assertContentEquals(payload.copyOfRange(17, payload.size), first.readByteArray())
                    assertContentEquals(payload.copyOfRange(31, payload.size), second.readByteArray())
                }
            }
            assertPositionalRead(payload, storage::readAt, 0)
            storage.close()
            assertFailsWith<IllegalStateException> { storage.readAt(0, ByteArray(1)) }
        }
    }
}
