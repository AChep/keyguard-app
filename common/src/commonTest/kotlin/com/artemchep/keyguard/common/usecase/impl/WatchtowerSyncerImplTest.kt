package com.artemchep.keyguard.common.usecase.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class WatchtowerSyncerImplTest {
    @Test
    fun `initial empty sync set permits immediate processing`() = runTest {
        val syncs = MutableStateFlow<Set<String>>(emptySet())
        val values = mutableListOf<Boolean>()

        backgroundScope.launch {
            syncs
                .watchtowerProcessingAllowedFlow()
                .toList(values)
        }
        runCurrent()

        assertEquals(listOf(true), values)
    }

    @Test
    fun `active sync pauses processing`() = runTest {
        val syncs = MutableStateFlow(setOf("account"))
        val values = mutableListOf<Boolean>()

        backgroundScope.launch {
            syncs
                .watchtowerProcessingAllowedFlow()
                .toList(values)
        }
        runCurrent()

        assertEquals(listOf(false), values)
    }

    @Test
    fun `processing resumes after sync cooldown`() = runTest {
        val syncs = MutableStateFlow(setOf("account"))
        val values = mutableListOf<Boolean>()

        backgroundScope.launch {
            syncs
                .watchtowerProcessingAllowedFlow()
                .toList(values)
        }
        runCurrent()

        syncs.value = emptySet()
        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()

        assertEquals(listOf(false), values)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf(false, true), values)
    }

    @Test
    fun `new sync during cooldown cancels pending resume`() = runTest {
        val syncs = MutableStateFlow(setOf("account-1"))
        val values = mutableListOf<Boolean>()

        backgroundScope.launch {
            syncs
                .watchtowerProcessingAllowedFlow()
                .toList(values)
        }
        runCurrent()

        syncs.value = emptySet()
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()
        syncs.value = setOf("account-2")
        runCurrent()
        syncs.value = emptySet()
        runCurrent()
        advanceTimeBy(1_999L)
        runCurrent()

        assertEquals(listOf(false), values)

        advanceTimeBy(1L)
        runCurrent()

        assertEquals(listOf(false, true), values)
    }

    @Test
    fun `initially allowed request gate emits first request immediately`() = runTest {
        val allowed = MutableStateFlow(true)
        val requests = MutableSharedFlow<Int>()
        val values = mutableListOf<Int>()

        backgroundScope.launch {
            requests
                .gateLatestWhenAllowed(allowed)
                .toList(values)
        }
        runCurrent()

        requests.emit(1)
        runCurrent()

        assertEquals(listOf(1), values)
    }

    @Test
    fun `paused request gate resumes with latest request only`() = runTest {
        val allowed = MutableStateFlow(false)
        val requests = MutableSharedFlow<Int>()
        val values = mutableListOf<Int>()

        backgroundScope.launch {
            requests
                .gateLatestWhenAllowed(allowed)
                .toList(values)
        }
        runCurrent()

        requests.emit(1)
        requests.emit(2)
        runCurrent()

        assertEquals(emptyList(), values)

        allowed.value = true
        runCurrent()

        assertEquals(listOf(2), values)

        requests.emit(3)
        runCurrent()

        assertEquals(listOf(2, 3), values)

        allowed.value = false
        runCurrent()
        allowed.value = true
        runCurrent()

        assertEquals(listOf(2, 3), values)

        allowed.value = false
        runCurrent()
        requests.emit(4)
        requests.emit(5)
        runCurrent()
        allowed.value = true
        runCurrent()

        assertEquals(listOf(2, 3, 5), values)
    }
}
