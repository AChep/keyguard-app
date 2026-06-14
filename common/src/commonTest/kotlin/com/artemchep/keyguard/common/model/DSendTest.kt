package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun createSend(
    expirationDate: Instant?,
) = DSend(
    id = "send-1",
    accountId = "account-1",
    accessId = "access-1",
    keyBase64 = "send-key",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = null,
    expirationDate = expirationDate,
    service = BitwardenService(),
    authType = DSend.AuthType.None,
    name = "Send",
    notes = "",
    accessCount = 0,
    hasPassword = false,
    synced = true,
    disabled = false,
    hideEmail = false,
    emails = emptyList(),
    type = DSend.Type.Text,
    text = DSend.Text(
        text = "body",
        hidden = false,
    ),
    file = null,
)
