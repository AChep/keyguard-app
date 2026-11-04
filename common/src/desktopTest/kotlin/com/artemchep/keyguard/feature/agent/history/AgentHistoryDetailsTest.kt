package com.artemchep.keyguard.feature.agent.history

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.common.model.DGpgUsageHistory
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.GpgUsageHistoryRequestType
import com.artemchep.keyguard.common.model.GpgUsageHistoryResponseType
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.common.service.agent.CallerIdentity
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.feature.cliagent.history.cliAgentHistoryDetails
import com.artemchep.keyguard.feature.gpgagent.history.gpgAgentHistoryDetails
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.sshagent.history.sshAgentHistoryDetails
import com.artemchep.keyguard.test.createSecret
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentHistoryDetailsTest {
    private val json = Json

    private val translator = object : TranslatorScope {
        override suspend fun translate(res: StringResource): String = res.key

        override suspend fun translate(res: StringResource, vararg args: Any): String =
            listOf(res.key, *args).joinToString(separator = ":")

        override suspend fun translate(
            res: PluralStringResource,
            quantity: Int,
            vararg args: Any,
        ): String = error("Unexpected plural translation")
    }

    private fun caller(
        pid: Int = 42,
        processName: String = "ssh",
        executablePath: String = "/usr/bin/ssh",
        appName: String = "Terminal",
        appBundlePath: String = "/Applications/Terminal.app",
    ) = json.encodeToString(
        CallerIdentity(
            pid = pid,
            processName = processName,
            executablePath = executablePath,
            appName = appName,
            appBundlePath = appBundlePath,
        ),
    )

    private fun AgentHistoryDetails.section(id: String) = sections.firstOrNull { it.id == id }

    private fun AgentHistoryDetails.Section.text(id: String) = rows
        .filterIsInstance<AgentHistoryDetails.Row.Text>()
        .firstOrNull { it.id == id }

    @Test
    fun `ssh sign lists the key and every part of the caller`() = runTest {
        val cipher = createSecret(id = "key-id", name = "Work key", type = DSecret.Type.SshKey)
        val details = with(translator) {
            sshAgentHistoryDetails(
                id = "id",
                event = DSshUsageHistory(
                    cipherId = "key-id",
                    sessionId = "session",
                    caller = caller(),
                    request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
                    response = SshUsageHistoryResponseType.SUCCESS,
                    fingerprint = "SHA256:abc",
                ),
                cipher = cipher,
                requestText = "Sign",
                responseText = "Success",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(AgentHistoryDetailsIcon.SSH_SIGN, details.icon)
        assertEquals(AgentHistoryOutcome.SUCCESS, details.outcome)
        assertEquals(listOf("key", "caller"), details.sections.map { it.id })

        val key = assertNotNull(details.section("key"))
        val reference = assertIs<AgentHistoryDetails.Row.Reference>(key.rows[0])
        assertEquals("key-id", reference.cipherId)
        assertEquals("account-id", reference.accountId)
        assertNotNull(reference.item)
        val fingerprint = assertNotNull(key.text("key.fingerprint"))
        assertEquals("SHA256:abc", fingerprint.value)
        assertEquals(CopyText.Type.FINGERPRINT, fingerprint.copyType)

        val callerSection = assertNotNull(details.section("caller"))
        assertEquals(
            listOf(
                "Terminal",
                "/Applications/Terminal.app",
                "ssh",
                "/usr/bin/ssh",
                "42",
            ),
            callerSection.rows.map { (it as AgentHistoryDetails.Row.Text).value },
        )
    }

    @Test
    fun `ssh list keys without a key has no key section`() = runTest {
        val details = with(translator) {
            sshAgentHistoryDetails(
                id = "id",
                event = DSshUsageHistory(
                    cipherId = null,
                    sessionId = "session",
                    caller = null,
                    request = SshUsageHistoryRequestType.AGENT_LIST_KEYS,
                    response = SshUsageHistoryResponseType.USER_DENIED,
                    fingerprint = null,
                ),
                cipher = null,
                requestText = "List keys",
                responseText = "Denied",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(AgentHistoryOutcome.FAILURE, details.outcome)
        assertNull(details.section("key"))
        // The caller falls back to the unknown caller.
        val callerSection = assertNotNull(details.section("caller"))
        assertEquals("agent_history_unknown_caller", callerSection.text("caller.unknown")?.value)
    }

    @Test
    fun `a malformed caller falls back to the unknown caller`() = runTest {
        val section = with(translator) {
            agentHistoryCallerSection(
                caller = "{not json",
                json = json,
                fallback = "unknown",
            )
        }
        assertEquals(listOf("unknown"), section.rows.map { (it as AgentHistoryDetails.Row.Text).value })
    }

    @Test
    fun `caller values are sanitized and an empty pid is hidden`() = runTest {
        val section = with(translator) {
            agentHistoryCallerSection(
                caller = caller(
                    pid = 0,
                    appName = "Evil‮exe.txt",
                    appBundlePath = "",
                    processName = "  ",
                    executablePath = "",
                ),
                json = json,
                fallback = "unknown",
            )
        }
        assertEquals(listOf("caller.app_name"), section.rows.map { it.id })
        val value = (section.rows.single() as AgentHistoryDetails.Row.Text).value
        assertTrue('‮' !in value, value)
    }

    @Test
    fun `gpg decrypt of a deleted key keeps an unavailable reference and the keygrip`() = runTest {
        val details = with(translator) {
            gpgAgentHistoryDetails(
                id = "id",
                event = DGpgUsageHistory(
                    cipherId = "deleted",
                    sessionId = "session",
                    caller = caller(),
                    request = GpgUsageHistoryRequestType.AGENT_DECRYPT,
                    response = GpgUsageHistoryResponseType.UNSUPPORTED,
                    fingerprint = "ABCD",
                    keygrip = "1234",
                ),
                cipher = null,
                requestText = "Decrypt",
                responseText = "Unsupported",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(AgentHistoryDetailsIcon.GPG_DECRYPT, details.icon)
        assertEquals(AgentHistoryOutcome.FAILURE, details.outcome)
        val key = assertNotNull(details.section("key"))
        val reference = assertIs<AgentHistoryDetails.Row.Reference>(key.rows[0])
        assertNull(reference.item)
        assertNull(reference.accountId)
        assertEquals("1234", key.text("key.keygrip")?.value)
    }

    @Test
    fun `cli run shows the command, its directory and the client`() = runTest {
        val environment = createSecret(id = "env", name = "Prod", type = DSecret.Type.Environment)
        val details = with(translator) {
            cliAgentHistoryDetails(
                id = "id",
                event = DCliAgentUsageHistory(
                    cipherId = "env",
                    sessionId = "session",
                    client = CliAgentUsageHistoryClient.CLI,
                    caller = caller(),
                    clientName = null,
                    request = CliAgentUsageHistoryRequestType.RUN,
                    response = CliAgentUsageHistoryResponseType.SUCCESS,
                    errorCode = null,
                    command = "npm start",
                    path = "/project",
                ),
                cipher = environment,
                requestText = "Run",
                responseText = "Success",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(listOf("environment", "command", "caller"), details.sections.map { it.id })
        val command = assertNotNull(details.section("command"))
        val code = assertIs<AgentHistoryDetails.Row.Code>(command.rows[0])
        assertEquals("npm start", code.value)
        val path = assertNotNull(command.text("command.path"))
        assertEquals("/project", path.value)
        assertEquals("agent_history_details_working_directory", path.label)
        assertNull(path.icon)
        // The environment speaks for itself, the caller is a table.
        assertNull(details.section("environment")?.title)
        val callerSection = assertNotNull(details.section("caller"))
        assertTrue(callerSection.table)
        assertEquals("cli_title", callerSection.text("caller.client")?.value)
    }

    @Test
    fun `cli env read without a caller names the local process`() = runTest {
        val details = with(translator) {
            cliAgentHistoryDetails(
                id = "id",
                event = DCliAgentUsageHistory(
                    cipherId = null,
                    sessionId = "session",
                    client = CliAgentUsageHistoryClient.ENV,
                    caller = null,
                    clientName = null,
                    request = CliAgentUsageHistoryRequestType.READ_LOCAL_ENV_FILE,
                    response = CliAgentUsageHistoryResponseType.CANCELLED,
                    errorCode = null,
                    command = null,
                    path = "/project/.env",
                ),
                cipher = null,
                requestText = "Read",
                responseText = "Cancelled",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(AgentHistoryDetailsIcon.ENV_FILE, details.icon)
        assertEquals(AgentHistoryOutcome.NEUTRAL, details.outcome)
        val command = assertNotNull(details.section("command"))
        val path = assertNotNull(command.text("command.path"))
        assertNull(path.icon)
        assertEquals("agent_approval_path_label", path.label)
        val callerSection = assertNotNull(details.section("caller"))
        assertEquals("environments_local_process", callerSection.text("caller.unknown")?.value)
    }

    @Test
    fun `cli mcp failure leads with the error code and the reported client name`() = runTest {
        val details = with(translator) {
            cliAgentHistoryDetails(
                id = "id",
                event = DCliAgentUsageHistory(
                    cipherId = null,
                    sessionId = "session",
                    client = CliAgentUsageHistoryClient.MCP,
                    caller = null,
                    clientName = "Claude Code",
                    request = CliAgentUsageHistoryRequestType.MCP_APPEND_VARIABLES,
                    response = CliAgentUsageHistoryResponseType.FAILURE,
                    errorCode = "operation_failed",
                    command = null,
                    path = null,
                ),
                cipher = null,
                requestText = "Update",
                responseText = "Failed",
                formattedDate = "Today",
                json = json,
            )
        }

        assertEquals(AgentHistoryDetailsIcon.MCP, details.icon)
        assertEquals(listOf("error", "caller"), details.sections.map { it.id })
        assertEquals("operation_failed", details.section("error")?.text("error.code")?.value)
        assertEquals("Claude Code", details.section("caller")?.text("caller.client_name")?.value)
    }
}
