package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EventFlowTest {
    @Test
    fun `starts inactive and toggles lifecycle only around first and last collector`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()

        assertFalse(eventFlow.isActive)
        assertEquals(emptyList(), eventFlow.lifecycle)

        val firstValues = mutableListOf<Int>()
        val firstCollector = collectInto(eventFlow, firstValues)

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active"), eventFlow.lifecycle)

        val secondValues = mutableListOf<Int>()
        val secondCollector = collectInto(eventFlow.asFlow(), secondValues)

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active"), eventFlow.lifecycle)

        firstCollector.cancelAndJoin()

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active"), eventFlow.lifecycle)

        secondCollector.cancelAndJoin()

        assertFalse(eventFlow.isActive)
        assertEquals(listOf("active", "inactive"), eventFlow.lifecycle)

        val thirdValues = mutableListOf<Int>()
        val thirdCollector = collectInto(eventFlow, thirdValues)

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active", "inactive", "active"), eventFlow.lifecycle)

        thirdCollector.cancelAndJoin()

        assertFalse(eventFlow.isActive)
        assertEquals(listOf("active", "inactive", "active", "inactive"), eventFlow.lifecycle)
    }

    @Test
    fun `emits every event to every active collector in order`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()
        val firstValues = mutableListOf<Int>()
        val secondValues = mutableListOf<Int>()
        val firstCollector = collectInto(eventFlow, firstValues)
        val secondCollector = collectInto(eventFlow, secondValues)

        eventFlow.emit(1)
        eventFlow.emit(2)
        eventFlow.emit(3)

        assertEquals(listOf(1, 2, 3), firstValues)
        assertEquals(listOf(1, 2, 3), secondValues)

        firstCollector.cancelAndJoin()
        secondCollector.cancelAndJoin()
    }

    @Test
    fun `emissions without active collectors are dropped and not replayed`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()

        eventFlow.emit(1)
        advanceUntilIdle()

        val values = mutableListOf<Int>()
        val collector = collectInto(eventFlow, values)

        advanceUntilIdle()

        assertEquals(emptyList(), values)

        eventFlow.emit(2)
        advanceUntilIdle()

        assertEquals(listOf(2), values)

        collector.cancelAndJoin()
    }

    @Test
    fun `cancelled collectors are removed before later emissions`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()
        val firstValues = mutableListOf<Int>()
        val secondValues = mutableListOf<Int>()
        val firstCollector = collectInto(eventFlow, firstValues)
        val secondCollector = collectInto(eventFlow, secondValues)

        firstCollector.cancelAndJoin()

        assertTrue(eventFlow.isActive)

        eventFlow.emit(7)
        advanceUntilIdle()

        assertEquals(emptyList(), firstValues)
        assertEquals(listOf(7), secondValues)

        secondCollector.cancelAndJoin()

        assertFalse(eventFlow.isActive)

        eventFlow.emit(8)
        advanceUntilIdle()

        assertEquals(listOf(7), secondValues)
        assertEquals(listOf("active", "inactive"), eventFlow.lifecycle)
    }

    @Test
    fun `emit targets collectors active when emit was called`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()
        val firstValues = mutableListOf<Int>()
        val firstCollector = collectInto(eventFlow, firstValues)

        eventFlow.emit(1)

        assertEquals(listOf(1), firstValues)

        val secondValues = mutableListOf<Int>()
        val secondCollector = collectInto(eventFlow, secondValues)

        advanceUntilIdle()

        assertEquals(listOf(1), firstValues)
        assertEquals(emptyList(), secondValues)

        eventFlow.emit(2)
        advanceUntilIdle()

        assertEquals(listOf(1, 2), firstValues)
        assertEquals(listOf(2), secondValues)

        firstCollector.cancelAndJoin()
        secondCollector.cancelAndJoin()
    }

    @Test
    fun `collector completion cleans up lifecycle`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()
        val values = mutableListOf<Int>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            eventFlow
                .take(1)
                .toList(values)
        }

        assertTrue(eventFlow.isActive)

        eventFlow.emit(42)
        advanceUntilIdle()
        collector.join()

        assertEquals(listOf(42), values)
        assertFalse(eventFlow.isActive)
        assertEquals(listOf("active", "inactive"), eventFlow.lifecycle)
    }

    @Test
    fun `many collectors share one active lifecycle and clean up after the last cancellation`() = runTest {
        val eventFlow = RecordingEventFlow<Int>()
        val values = List(8) { mutableListOf<Int>() }
        val collectors = values.map { collectInto(eventFlow, it) }

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active"), eventFlow.lifecycle)

        eventFlow.emit(5)
        advanceUntilIdle()

        values.forEach {
            assertEquals(listOf(5), it)
        }

        collectors.take(4).forEach {
            it.cancelAndJoin()
        }

        assertTrue(eventFlow.isActive)
        assertEquals(listOf("active"), eventFlow.lifecycle)

        eventFlow.emit(6)
        advanceUntilIdle()

        values.take(4).forEach {
            assertEquals(listOf(5), it)
        }
        values.drop(4).forEach {
            assertEquals(listOf(5, 6), it)
        }

        collectors.drop(4).forEach {
            it.cancelAndJoin()
        }

        assertFalse(eventFlow.isActive)
        assertEquals(listOf("active", "inactive"), eventFlow.lifecycle)
    }

    private fun <T> TestScope.collectInto(
        flow: Flow<T>,
        values: MutableList<T>,
    ): Job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        flow.collect { value ->
            values += value
        }
    }

    private class RecordingEventFlow<T> : EventFlow<T>() {
        val lifecycle = mutableListOf<String>()

        override fun onActive() {
            lifecycle += "active"
        }

        override fun onInactive() {
            lifecycle += "inactive"
        }
    }
}
