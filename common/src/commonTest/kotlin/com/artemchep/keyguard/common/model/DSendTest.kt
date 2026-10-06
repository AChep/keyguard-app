package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.test.createSend
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

class DSendTest {
    @Test
    fun `expired flow emits false for sends without expiration`() = runTest {
        val send = createSend(
            expirationDate = null,
        )

        assertEquals(
            expected = listOf(false),
            actual = send.expiredFlow.toList(),
        )
    }

    @Test
    fun `expired flow emits true for already expired sends`() = runTest {
        val send = createSend(
            expirationDate = Clock.System.now() - 1.seconds,
        )

        assertEquals(
            expected = listOf(true),
            actual = send.expiredFlow.toList(),
        )
    }

    @Test
    fun `expired flow emits false then true for future expiration`() = runTest {
        val send = createSend(
            expirationDate = Clock.System.now() + 5.seconds,
        )

        assertEquals(
            expected = listOf(false, true),
            actual = send.expiredFlow.toList(),
        )
    }
}
