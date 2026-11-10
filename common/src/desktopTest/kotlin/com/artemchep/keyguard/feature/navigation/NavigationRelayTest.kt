package com.artemchep.keyguard.feature.navigation

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NavigationRelayTest {
    private val relay = NavigationRelay()

    @Test
    fun `a lone task runs on the calling thread before submit returns`() {
        val caller = Thread.currentThread()
        var ranOn: Thread? = null

        relay.submit { ranOn = Thread.currentThread() }

        assertSame(caller, ranOn)
    }

    @Test
    fun `tasks submitted from a task run in order after it and before the outer submit returns`() {
        val log = mutableListOf<String>()

        relay.submit {
            log += "outer start"
            relay.submit { log += "a" }
            relay.submit {
                log += "b"
                relay.submit { log += "d" }
            }
            relay.submit { log += "c" }
            log += "outer end"
        }

        assertEquals(listOf("outer start", "outer end", "a", "b", "c", "d"), log)
    }

    @Test
    fun `a failing task does not stop the queue and its failure reaches the drainer`() {
        val log = mutableListOf<String>()
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")

        val thrown = assertFailsWith<IllegalStateException> {
            relay.submit {
                relay.submit { throw first }
                relay.submit { log += "after first" }
                relay.submit { throw second }
                relay.submit { log += "after second" }
                log += "outer"
            }
        }

        assertSame(first, thrown)
        assertEquals(listOf(second), thrown.suppressed.toList())
        assertEquals(listOf("outer", "after first", "after second"), log)

        // The relay is idle again.
        var ran = false
        relay.submit { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `a lone failing task releases the relay`() {
        assertFailsWith<IllegalStateException> {
            relay.submit { error("failure") }
        }

        var ran = false
        relay.submit { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `a task submitted from another thread while draining runs on the draining thread`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val log = Collections.synchronizedList(mutableListOf<String>())
        var otherRanOn: Thread? = null

        val drainer = thread(name = "drainer") {
            relay.submit {
                started.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                log += "first"
            }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))

        // Returns at once: the drainer runs it after its current task.
        relay.submit {
            otherRanOn = Thread.currentThread()
            log += "second"
        }
        assertEquals(emptyList(), log.toList())

        release.countDown()
        drainer.join(10_000)
        assertFalse(drainer.isAlive)
        assertEquals(listOf("first", "second"), log.toList())
        assertSame(drainer, otherRanOn)
    }

    @Test
    fun `tasks from many threads never overlap and each runs exactly once`() {
        val threads = 8
        val tasksPerThread = 2_000
        val runs = AtomicIntegerArray(threads * tasksPerThread)
        val running = AtomicBoolean(false)
        val overlaps = AtomicInteger(0)
        val barrier = CyclicBarrier(threads)

        val workers = List(threads) { t ->
            thread(name = "submitter-$t") {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(tasksPerThread) { i ->
                    val index = t * tasksPerThread + i
                    relay.submit {
                        if (!running.compareAndSet(false, true)) {
                            overlaps.incrementAndGet()
                        }
                        runs.incrementAndGet(index)
                        running.set(false)
                    }
                }
            }
        }
        workers.forEach { it.join(60_000) }

        assertTrue(workers.none { it.isAlive })
        assertEquals(0, overlaps.get())
        // Every submit has returned, so the last drainer emptied the queue.
        val counts = List(runs.length()) { runs.get(it) }
        assertEquals(List(runs.length()) { 1 }, counts)
    }
}
