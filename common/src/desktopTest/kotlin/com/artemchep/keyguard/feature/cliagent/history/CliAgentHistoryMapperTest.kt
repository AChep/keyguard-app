package com.artemchep.keyguard.feature.cliagent.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CliAgentHistoryMapperTest {
    @Test
    fun `description joins the known parts in order`() {
        val description = buildCliAgentUsageHistoryDescription(
            request = "Run a command",
            environment = "Production",
            clientName = "Client name (unverified): Claude Code",
            caller = "/usr/bin/zsh",
            errorCode = "operation_failed",
        )

        assertEquals(
            "Run a command • Production • Client name (unverified): Claude Code • /usr/bin/zsh • operation_failed",
            description,
        )
    }

    @Test
    fun `description skips the missing parts`() {
        val description = buildCliAgentUsageHistoryDescription(
            request = "Read a local .env file",
            environment = null,
            clientName = null,
            caller = null,
            errorCode = null,
        )

        assertEquals("Read a local .env file", description)
    }

    @Test
    fun `details put the command and its directory on separate lines`() {
        assertEquals("npm start\nIn /project", buildCliAgentUsageHistoryDetails("npm start", "In /project"))
        assertEquals("/tmp/app.env", buildCliAgentUsageHistoryDetails(null, "/tmp/app.env"))
        assertNull(buildCliAgentUsageHistoryDetails(null, null))
    }
}
