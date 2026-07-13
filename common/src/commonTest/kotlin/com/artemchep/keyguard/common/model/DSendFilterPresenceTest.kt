package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import kotlinx.coroutines.test.runTest
import org.kodein.di.DI
import org.kodein.di.direct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class DSendFilterPresenceTest {
    @Test
    fun `existsIn matches full-scan any for every primitive`() = runTest {
        val sends = listOf(
            createSend(
                id = "text-a",
                accountId = "account-a",
                type = DSend.Type.Text,
            ),
            createSend(
                id = "file-b",
                accountId = "account-b",
                type = DSend.Type.File,
            ),
        )
        val presence = DSendFilterPresence.of(sends) { it }
        val di = DI {}.direct

        val primitives = listOf(
            // ById / ACCOUNT.
            DSendFilter.ById("account-a", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById("account-b", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById("account-missing", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById(null, DSendFilter.ById.What.ACCOUNT),
            // ByType.
            DSendFilter.ByType(DSend.Type.Text),
            DSendFilter.ByType(DSend.Type.File),
            DSendFilter.ByType(DSend.Type.None),
        )

        primitives.forEach { primitive ->
            val predicate = primitive.prepare(di, sends)
            val expected = sends.any(predicate)
            assertEquals(
                expected = expected,
                actual = primitive.existsIn(presence),
                message = "existsIn mismatch for $primitive",
            )
        }
    }

    @Test
    fun `existsIn is false for every primitive on an empty list`() = runTest {
        val presence = DSendFilterPresence.of(emptyList<DSend>()) { it }

        val primitives = listOf(
            DSendFilter.ById("account-a", DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ById(null, DSendFilter.ById.What.ACCOUNT),
            DSendFilter.ByType(DSend.Type.Text),
            DSendFilter.ByType(DSend.Type.File),
        )

        primitives.forEach { primitive ->
            assertFalse(
                actual = primitive.existsIn(presence) == true,
                message = "existsIn should be false for $primitive on an empty index",
            )
        }
    }
}

private val TEST_INSTANT = Instant.parse("2024-01-01T00:00:00Z")

private fun createSend(
    id: String = "send-1",
    accountId: String = "account-1",
    type: DSend.Type = DSend.Type.Text,
) = DSend(
    id = id,
    accountId = accountId,
    accessId = "access-1",
    keyBase64 = "send-key",
    revisionDate = TEST_INSTANT,
    createdDate = TEST_INSTANT,
    deletedDate = null,
    expirationDate = null,
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
    type = type,
    text = DSend.Text(
        text = "body",
        hidden = false,
    ),
    file = null,
)
