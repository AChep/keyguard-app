package com.artemchep.keyguard.common.io

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

class IOResourceControlTest {
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
