package com.artemchep.keyguard.common.service.dragout

import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.test.RecordingLogRepository
import com.artemchep.keyguard.test.TestByteSnapshot
import com.artemchep.keyguard.test.testPreparedContentStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PreparedContentStoreTest {
    @Test
    fun `independent readers retain content after offer closes and final close happens once`() = runTest {
        val fixture = fixture()
        val snapshot = TestByteSnapshot("attachment".encodeToByteArray())
        val content = fixture.store.reserve().publish("../CON.txt", snapshot)
        val first = content.openReader()
        val second = content.openReader()
        assertEquals("_CON.txt", content.name)
        assertEquals(snapshot.size, content.size)
        val head = ByteArray(1)
        assertEquals(1, first.readAt(0, head))
        assertEquals('a'.code.toByte(), head.single())
        content.close()
        content.close()
        runCurrent()
        assertEquals(0, snapshot.closes)
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertContentEquals("attachment".encodeToByteArray(), second.readFrom(0))
        second.close()
        second.close()
        runCurrent()
        assertEquals(0, snapshot.closes)
        assertContentEquals("ttachment".encodeToByteArray(), first.readFrom(1))
        first.close()
        first.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `never handed off content expires and releases its allowance`() = runTest {
        val fixture = fixture(maximumBytes = 1)
        val snapshot = TestByteSnapshot(byteArrayOf(1))
        val content = fixture.store.reserve().publish("file", snapshot)
        assertFailsWith<IllegalStateException> { fixture.store.reserve() }
        advanceTimeBy(LIFETIME)
        runCurrent()
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertEquals(1, snapshot.closes)
        fixture.store.reserve().close()
    }

    @Test
    fun `first handoff extends deadline once and expiry does not interrupt active reader`() = runTest {
        val fixture = fixture()
        val snapshot = TestByteSnapshot(byteArrayOf(1, 2, 3))
        val content = fixture.store.reserve().publish("file", snapshot)
        advanceTimeBy(60)
        content.handoff()
        advanceTimeBy(60)
        content.handoff()
        val reader = content.openReader()
        advanceTimeBy(40)
        runCurrent()
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertEquals(0, snapshot.closes)
        assertContentEquals(byteArrayOf(1, 2, 3), reader.readFrom(0))
        reader.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `open checks deadline before its delayed task is dispatched`() = runTest {
        val fixture = fixture()
        val snapshot = TestByteSnapshot(byteArrayOf(1))
        val content = fixture.store.reserve().publish("file", snapshot)
        advanceTimeBy(LIFETIME)
        // Tasks at the new time have not been run yet.
        assertFailsWith<IllegalStateException> { content.openReader() }
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `reservation rejects overlap and reserves aggregate allowance before staging`() = runTest {
        val fixture = fixture(maximumBytes = 12)
        val first = fixture.store.reserve()
        assertEquals(12L, first.limits.maximumBytes)
        assertFailsWith<IllegalStateException> { fixture.store.reserve() }
        val content = first.publish("first", TestByteSnapshot(ByteArray(7)))
        first.close()
        val second = fixture.store.reserve()
        assertEquals(5L, second.limits.maximumBytes)
        assertEquals(5L, second.limits.memoryBytes)
        assertFailsWith<IllegalArgumentException> { second.publish("oversize", TestByteSnapshot(ByteArray(6))) }
        second.close()
        content.close()
        runCurrent()
        fixture.store.reserve().use { assertEquals(12L, it.limits.maximumBytes) }
    }

    @Test
    fun `closed offers with active readers still count against the byte budget`() = runTest {
        val fixture = fixture(maximumBytes = 8)
        val content = fixture.store.reserve().publish("file", TestByteSnapshot(ByteArray(8)))
        val reader = content.openReader()
        content.close()
        runCurrent()
        assertFailsWith<IllegalStateException> { fixture.store.reserve() }
        reader.close()
        runCurrent()
        fixture.store.reserve().close()
    }

    @Test
    fun `store shutdown rejects new work while readers finish and closes storage after scope cancellation`() = runTest {
        val lifetime = Job()
        val fixture = fixture(parentJob = lifetime)
        val snapshot = TestByteSnapshot(byteArrayOf(3, 2, 1))
        val content = fixture.store.reserve().publish("file", snapshot)
        val reader = content.openReader()
        lifetime.cancel()
        runCurrent()
        assertFailsWith<IllegalStateException> { fixture.store.reserve() }
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertContentEquals(byteArrayOf(3, 2, 1), reader.readFrom(0))
        reader.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
    }

    @Test
    fun `cleanup failure is redacted and keeps its allowance charged`() = runTest {
        val fixture = fixture(maximumBytes = 1)
        val snapshot = TestByteSnapshot(byteArrayOf(1), failClose = true)
        val content = fixture.store.reserve().publish("private-name", snapshot)
        content.close()
        content.close()
        runCurrent()
        assertEquals(1, snapshot.closes)
        assertEquals(listOf(CLEANUP_FAILURE to LogLevel.ERROR), fixture.log.levels())
        assertFailsWith<IllegalStateException> { fixture.store.reserve() }
    }

    @Test
    fun `failed publication leaves snapshot ownership with caller and abort is idempotent`() = runTest {
        val fixture = fixture()
        val reservation = fixture.store.reserve()
        reservation.close()
        reservation.close()
        val snapshot = TestByteSnapshot(byteArrayOf(1))
        assertFailsWith<IllegalStateException> { reservation.publish("file", snapshot) }
        assertEquals(0, snapshot.closes)
        snapshot.close()
        fixture.store.reserve().close()
    }

    @Test
    fun `retirement callbacks run once on expiry, close, or immediately after retirement`() = runTest {
        val fixture = fixture()
        val expiring = fixture.store.reserve().publish("first", TestByteSnapshot(byteArrayOf(1)))
        var expired = 0
        var unsubscribed = 0
        expiring.invokeOnRetirement { expired += 1 }
        expiring.invokeOnRetirement { unsubscribed += 1 }.close()
        advanceTimeBy(LIFETIME)
        runCurrent()
        assertEquals(1, expired)
        assertEquals(0, unsubscribed)

        val closing = fixture.store.reserve().publish("second", TestByteSnapshot(byteArrayOf(2)))
        var closed = 0
        closing.invokeOnRetirement { closed += 1 }
        closing.close()
        closing.close()
        assertEquals(1, closed)

        var late = 0
        closing.invokeOnRetirement { late += 1 }
        assertEquals(1, late)
    }

    @Test
    fun `owner completion retires handed-off content while its open reader finishes`() = runTest {
        val fixture = fixture()
        val owner = Job()
        val snapshot = TestByteSnapshot(byteArrayOf(1, 2, 3))
        val content = fixture.store.reserve(owner = owner).publish("file", snapshot)
        content.handoff()
        val reader = content.openReader()
        var retired = 0
        content.invokeOnRetirement { retired += 1 }
        owner.cancel()
        assertEquals(1, retired)
        assertFailsWith<IllegalStateException> { content.openReader() }
        assertContentEquals(byteArrayOf(1, 2, 3), reader.readFrom(0))
        reader.close()
        runCurrent()
        assertEquals(1, snapshot.closes)

        val late = fixture.store.reserve(owner = owner).publish("late", TestByteSnapshot(byteArrayOf(4)))
        assertFailsWith<IllegalStateException> { late.openReader() }
    }

    @Test
    fun `a failing retirement callback is logged and does not skip the others`() = runTest {
        val fixture = fixture()
        val content = fixture.store.reserve().publish("file", TestByteSnapshot(byteArrayOf(1)))
        var after = 0
        content.invokeOnRetirement { error("/private/path/must/not/be/logged") }
        content.invokeOnRetirement { after += 1 }
        content.close()
        assertEquals(1, after)
        assertEquals(listOf(CLEANUP_FAILURE to LogLevel.ERROR), fixture.log.levels())
    }

    private fun TestScope.fixture(
        maximumReaders: Int = 8,
        maximumBytes: Long = 64,
        parentJob: Job? = null,
    ): Fixture {
        val log = RecordingLogRepository()
        val scope = if (parentJob == null) backgroundScope else {
            CoroutineScope(backgroundScope.coroutineContext + parentJob)
        }
        val limits = PreparedContentLimits(
            maximumReaders = maximumReaders,
            maximumBytes = maximumBytes,
            memoryBytes = minOf(8, maximumBytes),
            offerLifetimeMillis = LIFETIME,
        )
        return Fixture(store = testPreparedContentStore(limits, log, scope), log = log)
    }

    private data class Fixture(val store: PreparedContentStore, val log: RecordingLogRepository)

    private fun RecordingLogRepository.levels() = entries.map { it.message to it.level }

    private companion object {
        const val LIFETIME = 100L
        const val CLEANUP_FAILURE = "Failed to close prepared content storage"
    }
}
