package com.artemchep.keyguard.common.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class IOResourceControlTest {
    @Test
    fun `bracket calls release after successful use`() = runTest {
        var released: Int? = null
        val result = io(10).bracket(
            release = { resource ->
                ioEffect {
                    released = resource
                }
            },
            use = { resource ->
                io(resource + 1)
            },
        ).bind()

        assertEquals(11, result)
        assertEquals(10, released)
    }

    @Test
    fun `bracket calls release after failed use`() = runTest {
        var released: Int? = null

        assertFailsWith<IllegalStateException> {
            io(20).bracket(
                release = { resource ->
                    ioEffect {
                        released = resource
                    }
                },
                use = {
                    ioRaise<Int>(IllegalStateException("use failed"))
                },
            ).bind()
        }

        assertEquals(20, released)
    }

    @Test
    fun `bracket propagates use failure after release`() = runTest {
        val e = IllegalStateException("use failed")
        var releaseCalled = false

        val thrown = assertFailsWith<IllegalStateException> {
            io(30).bracket(
                release = {
                    ioEffect {
                        releaseCalled = true
                    }
                },
                use = {
                    ioRaise<Int>(e)
                },
            ).bind()
        }

        assertTrue(releaseCalled)
        assertEquals(e.message, thrown.message)
    }

    @Test
    fun `bracket still runs release when caller is cancelled`() = runTest {
        val released = CompletableDeferred<Unit>()

        assertFailsWith<CancellationException> {
            io(40).bracket(
                release = {
                    ioEffect {
                        released.complete(Unit)
                    }
                },
                use = {
                    ioRaise<Int>(CancellationException("cancel"))
                },
            ).bind()
        }

        released.await()
    }

    @Test
    fun `dispatchOn executes IO and preserves value`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val value = io(50).dispatchOn(dispatcher).bind()
        assertEquals(50, value)
    }

    @Test
    fun `mutex serializes concurrent mutations`() = runTest {
        val mutex = Mutex()
        var active = 0
        var maxActive = 0
        var counter = 0

        val tasks = List(20) {
            async {
                ioEffect {
                    active += 1
                    maxActive = max(maxActive, active)

                    val before = counter
                    delay(1L)
                    counter = before + 1

                    active -= 1
                }.mutex(mutex).bind()
            }
        }
        tasks.awaitAll()

        assertEquals(20, counter)
        assertEquals(1, maxActive)
    }

    @Test
    fun `launchIn starts execution in provided scope`() = runTest {
        val started = CompletableDeferred<Unit>()
        val job = ioEffect {
            started.complete(Unit)
            60
        }.launchIn(this)

        started.await()
        job.join()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `measure invokes callback with original result and returns same value`() = runTest {
        var measuredDuration: Duration? = null
        var measuredValue: Int? = null
        val result = io(70).measure { duration, value ->
            measuredDuration = duration
            measuredValue = value
        }.bind()

        assertEquals(70, result)
        assertEquals(70, measuredValue)
        assertNotNull(measuredDuration)
        assertTrue(measuredDuration >= Duration.ZERO)
    }
}
