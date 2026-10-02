package com.artemchep.keyguard.util.ffi

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class JniLibraryTest {
    @Test
    fun missingLibraryThrowsTheModuleFailureWithoutWrapping() {
        // The module failure is an IOException, like util/io's.
        val library = JniLibrary(
            load = { false },
            unavailable = ::Unavailable,
            verify = {},
        )

        val error = assertFailsWith<Unavailable> { library.ensureLoaded() }
        assertNull(error.cause)
    }

    @Test
    fun loadingFailuresMapToUnavailableWithTheSameCause() {
        listOf(
            UnsatisfiedLinkError("link"),
            SecurityException("security"),
            IOException("canonical path"),
        ).forEach { failure ->
            val library = JniLibrary(
                load = { throw failure },
                unavailable = ::Unavailable,
                verify = {},
            )

            val error = assertFailsWith<Unavailable> { library.ensureLoaded() }
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun otherLoadingFailuresPassThrough() {
        val failure = IllegalStateException("unexpected")
        val library = JniLibrary(
            load = { throw failure },
            unavailable = ::Unavailable,
            verify = {},
        )

        assertSame(failure, assertFailsWith<IllegalStateException> { library.ensureLoaded() })
    }

    @Test
    fun verifyFailurePassesThroughAndIsRetried() {
        val loads = AtomicInteger()
        val verifies = AtomicInteger()
        val library = JniLibrary(
            load = {
                loads.incrementAndGet()
                true
            },
            unavailable = ::Unavailable,
            verify = {
                if (verifies.incrementAndGet() == 1) throw Protocol()
            },
        )

        assertFailsWith<Protocol> { library.ensureLoaded() }
        library.ensureLoaded()
        library.ensureLoaded()

        assertEquals(2, loads.get())
        assertEquals(2, verifies.get())
    }

    @Test
    fun missingSymbolDuringVerifyMapsToUnavailable() {
        val failure = UnsatisfiedLinkError("abiVersion")
        val library = JniLibrary(
            load = { true },
            unavailable = ::Unavailable,
            verify = { throw failure },
        )

        val error = assertFailsWith<Unavailable> { library.ensureLoaded() }
        assertSame(failure, error.cause)
    }

    @Test
    fun failedLoadIsRetried() {
        val loads = AtomicInteger()
        val library = JniLibrary(
            load = { loads.incrementAndGet() > 1 },
            unavailable = ::Unavailable,
            verify = {},
        )

        assertFailsWith<Unavailable> { library.ensureLoaded() }
        library.ensureLoaded()
        library.ensureLoaded()

        assertEquals(2, loads.get())
    }

    @Test
    fun concurrentCallersLoadOnce() {
        val loads = AtomicInteger()
        val library = JniLibrary(
            load = {
                loads.incrementAndGet()
                true
            },
            unavailable = ::Unavailable,
            verify = {},
        )
        val start = CountDownLatch(1)
        val threads = List(THREAD_COUNT) {
            thread {
                start.await()
                library.ensureLoaded()
            }
        }
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, loads.get())
    }

    private class Unavailable(cause: Throwable?) : IOException("unavailable", cause)

    private class Protocol : RuntimeException("protocol")
}

private const val THREAD_COUNT = 8
