package com.artemchep.keyguard.apple.gpgagent

import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.feature.gpgagent.history.GpgAgentHistoryItem
import com.artemchep.keyguard.feature.gpgagent.history.GpgAgentHistoryState
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Instant

class GpgAgentHistorySnapshotTest {
    @Test
    fun preservesSectionsAndEachOperationResult() {
        val state = GpgAgentHistoryState(
            subtitle = null,
            options = persistentListOf(),
            items = persistentListOf(
                GpgAgentHistoryItem.Section(id = "today", text = "Today"),
                item("sign", GpgUsageHistoryRequestType.AGENT_SIGN_HASH, GpgUsageHistoryResponseType.SUCCESS),
                item("decrypt", GpgUsageHistoryRequestType.AGENT_DECRYPT, GpgUsageHistoryResponseType.USER_DENIED),
                item("locked", GpgUsageHistoryRequestType.AGENT_DECRYPT, GpgUsageHistoryResponseType.VAULT_LOCKED),
            ),
        )
        val snapshot = state.toHistorySnapshot()
        assertEquals(GpgAgentHistoryItemKind.SECTION, snapshot.items[0].kind)
        assertEquals("Today", snapshot.items[0].caller)
        assertNull(snapshot.items[0].request)
        assertEquals("AGENT_SIGN_HASH", snapshot.items[1].request)
        assertEquals("SUCCESS", snapshot.items[1].response)
        assertEquals("AGENT_DECRYPT", snapshot.items[2].request)
        assertEquals("USER_DENIED", snapshot.items[2].response)
        assertEquals("VAULT_LOCKED", snapshot.items[3].response)
        assertFalse(snapshot.canClear)
        assertEquals(GpgAgentHistorySnapshot.empty, null.toHistorySnapshot())
    }

    private fun item(id: String, request: GpgUsageHistoryRequestType, response: GpgUsageHistoryResponseType) =
        GpgAgentHistoryItem.Value(
            id = id,
            caller = "Terminal",
            description = "Test key",
            formattedDate = "12:00",
            responseText = response.name,
            request = request,
            response = response,
            createdAt = Instant.fromEpochMilliseconds(0),
        )
}
