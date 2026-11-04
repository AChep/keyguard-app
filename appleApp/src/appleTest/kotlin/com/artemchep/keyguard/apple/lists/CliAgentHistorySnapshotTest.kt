package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetails
import com.artemchep.keyguard.feature.agent.history.AgentHistoryDetailsIcon
import com.artemchep.keyguard.feature.agent.history.AgentHistoryOutcome
import com.artemchep.keyguard.feature.cliagent.history.CliAgentHistoryItem
import com.artemchep.keyguard.feature.cliagent.history.CliAgentHistoryState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CliAgentHistorySnapshotTest {
    @Test
    fun preservesSectionsDetailsAndEachRequestResult() {
        val state = CliAgentHistoryState(
            subtitle = "Command line",
            options = persistentListOf(),
            items = persistentListOf(
                CliAgentHistoryItem.Section(id = "today", text = "Today"),
                item(
                    id = "run",
                    request = CliAgentUsageHistoryRequestType.RUN,
                    response = CliAgentUsageHistoryResponseType.SUCCESS,
                    commandText = "npm test\nIn /home/user/project",
                ),
                item(
                    id = "list",
                    request = CliAgentUsageHistoryRequestType.MCP_LIST_ENVIRONMENTS,
                    response = CliAgentUsageHistoryResponseType.USER_DENIED,
                ),
                item(
                    id = "read",
                    request = CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE,
                    response = CliAgentUsageHistoryResponseType.CANCELLED,
                    commandText = "/home/user/project/.env",
                ),
            ),
        )
        val snapshot = state.toHistorySnapshot()
        assertTrue(snapshot.loaded)
        assertEquals("Command line", snapshot.subtitle)
        assertEquals(CliAgentHistoryItemKind.SECTION, snapshot.items[0].kind)
        assertEquals("Today", snapshot.items[0].caller)
        assertNull(snapshot.items[0].commandText)
        assertNull(snapshot.items[0].response)
        assertEquals(CliAgentHistoryItemKind.VALUE, snapshot.items[1].kind)
        assertEquals("SUCCESS", snapshot.items[1].response)
        assertEquals("npm test\nIn /home/user/project", snapshot.items[1].commandText)
        assertEquals("USER_DENIED", snapshot.items[2].response)
        assertNull(snapshot.items[2].commandText)
        assertEquals("CANCELLED", snapshot.items[3].response)
        assertEquals("/home/user/project/.env", snapshot.items[3].commandText)
        assertNull(snapshot.items[0].details)
        assertEquals("Request", snapshot.items[1].details?.title)
        assertFalse(snapshot.canClear)
        assertEquals(CliAgentHistorySnapshot.empty, null.toHistorySnapshot())
    }

    @Test
    fun clearIsOfferedOnlyWithTheProducersOption() {
        val state = CliAgentHistoryState(
            subtitle = null,
            options = persistentListOf(
                FlatItemAction(
                    id = "cliAgentHistory.clearHistory",
                    title = TextHolder.Value("Clear history"),
                ),
            ),
            items = persistentListOf(
                item(
                    id = "run",
                    request = CliAgentUsageHistoryRequestType.RUN,
                    response = CliAgentUsageHistoryResponseType.TIMED_OUT,
                ),
            ),
        )
        assertTrue(state.toHistorySnapshot().canClear)
    }

    @Test
    fun clientNamesFallBackToUnknown() {
        CliAgentUsageHistoryClient.entries.forEach { client ->
            assertEquals(client, CliAgentUsageHistoryClient.ofName(client.name))
        }
        assertEquals(CliAgentUsageHistoryClient.UNKNOWN, CliAgentUsageHistoryClient.ofName("cli"))
        assertEquals(CliAgentUsageHistoryClient.UNKNOWN, CliAgentUsageHistoryClient.ofName(""))
    }

    private fun item(
        id: String,
        request: CliAgentUsageHistoryRequestType,
        response: CliAgentUsageHistoryResponseType,
        commandText: String? = null,
    ) = CliAgentHistoryItem.Value(
        id = id,
        caller = "Terminal",
        description = "Run a command • Production",
        commandText = commandText,
        formattedDate = "12:00",
        responseText = response.name,
        request = request,
        response = response,
        createdAt = Instant.fromEpochMilliseconds(0),
        details = testAgentHistoryDetails(id),
    )
}

internal fun testAgentHistoryDetails(id: String) = AgentHistoryDetails(
    id = id,
    icon = AgentHistoryDetailsIcon.UNKNOWN,
    title = "Request",
    outcome = AgentHistoryOutcome.NEUTRAL,
    outcomeText = "Unknown",
    formattedDate = "12:00",
    sections = emptyList(),
)

