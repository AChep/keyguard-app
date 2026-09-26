package com.artemchep.keyguard.android.ipc

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenPgpOutputPipeStoreTest {
    @Test
    fun `pipes are caller bound single use and closed on terminal paths`() {
        var now = 1_000L
        val store = OpenPgpOutputPipeStore<FakeDescriptor>(
            maxPerUid = 2,
            maxGlobal = 3,
            lifetimeMs = 60L,
            elapsedNow = { now },
        )
        val first = pipe()
        val read = store.create(1, 10, 1) { first }
        assertNotNull(read)
        assertNull(store.take(1, 11, 1))
        assertNull(store.take(2, 10, 1))
        assertFalse(first.second.closed)

        assertTrue(store.take(1, 10, 1) === first.second)
        assertNull(store.take(1, 10, 1))
        assertFalse(first.second.closed)
        first.second.close()

        val discarded = pipe()
        assertNotNull(store.create(1, 10, 2) { discarded })
        store.discard(1, 10, 2)
        assertTrue(discarded.second.closed)

        val expired = pipe()
        assertNotNull(store.create(1, 10, 3) { expired })
        now += 61L
        assertNull(store.take(1, 10, 3))
        assertTrue(expired.second.closed)

        val destroyed = pipe()
        assertNotNull(store.create(1, 10, 4) { destroyed })
        store.close()
        assertTrue(destroyed.second.closed)
    }

    @Test
    fun `per uid and global limits are enforced`() {
        val store = OpenPgpOutputPipeStore<FakeDescriptor>(
            maxPerUid = 2,
            maxGlobal = 3,
            lifetimeMs = 60L,
            elapsedNow = { 0L },
        )
        assertNotNull(store.create(1, 10, 1, ::pipe))
        assertNotNull(store.create(1, 10, 2, ::pipe))
        assertNull(store.create(1, 10, 3, ::pipe))
        assertNull(store.create(1, 10, 2, ::pipe))
        assertNotNull(store.create(2, 20, 1, ::pipe))
        assertNull(store.create(3, 30, 1, ::pipe))
        assertNull(store.create(4, 40, 0, ::pipe))
        store.close()
    }

    private fun pipe(): Pair<FakeDescriptor, FakeDescriptor> =
        FakeDescriptor() to FakeDescriptor()

    private class FakeDescriptor : Closeable {
        var closed = false
            private set

        override fun close() {
            closed = true
        }
    }
}
