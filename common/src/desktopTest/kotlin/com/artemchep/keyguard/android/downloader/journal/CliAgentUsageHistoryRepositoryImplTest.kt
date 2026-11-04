package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.model.CliAgentUsageHistoryClient
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryRequestType
import com.artemchep.keyguard.common.model.CliAgentUsageHistoryResponseType
import com.artemchep.keyguard.common.model.DCliAgentUsageHistory
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.insertLocalCipher
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBitwardenCipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class CliAgentUsageHistoryRepositoryImplTest {
    @Test
    fun `get recent lists the newest history of one client with limit`() = runTest {
        val repository = createRepository()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI, sessionId = "cli-1", millis = 1))()
        repository.put(model(client = CliAgentUsageHistoryClient.MCP, sessionId = "mcp-1", millis = 2))()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI, sessionId = "cli-2", millis = 3))()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI, sessionId = "cli-3", millis = 4))()

        val history = repository.getRecent(CliAgentUsageHistoryClient.CLI, limit = 2).first()

        assertEquals(listOf("cli-3", "cli-2"), history.map { it.sessionId })
    }

    @Test
    fun `get by cipher id lists the history of every client`() = runTest {
        val repository = createRepository(cipherIds = listOf("env-a", "env-b"))
        repository.put(
            model(client = CliAgentUsageHistoryClient.CLI, cipherId = "env-a", sessionId = "cli", millis = 1),
        )()
        repository.put(
            model(client = CliAgentUsageHistoryClient.MCP, cipherId = "env-b", sessionId = "other", millis = 2),
        )()
        repository.put(
            model(client = CliAgentUsageHistoryClient.ENV, cipherId = "env-a", sessionId = "env", millis = 3),
        )()

        val history = repository.getByCipherId(cipherId = "env-a").first()

        assertEquals(listOf("env", "cli"), history.map { it.sessionId })
    }

    @Test
    fun `get count counts the history of one client`() = runTest {
        val repository = createRepository()
        repository.put(model(client = CliAgentUsageHistoryClient.MCP))()
        repository.put(model(client = CliAgentUsageHistoryClient.MCP))()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI))()

        assertEquals(2L, repository.getCount(CliAgentUsageHistoryClient.MCP).first())
        assertEquals(1L, repository.getCount(CliAgentUsageHistoryClient.CLI).first())
        assertEquals(0L, repository.getCount(CliAgentUsageHistoryClient.ENV).first())
    }

    @Test
    fun `remove by client keeps the history of other clients`() = runTest {
        val repository = createRepository()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI))()
        repository.put(model(client = CliAgentUsageHistoryClient.MCP))()

        repository.removeByClient(CliAgentUsageHistoryClient.CLI)()

        assertEquals(0L, repository.getCount(CliAgentUsageHistoryClient.CLI).first())
        assertEquals(1L, repository.getCount(CliAgentUsageHistoryClient.MCP).first())
    }

    @Test
    fun `remove older than removes the old history of every client`() = runTest {
        val repository = createRepository()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI, sessionId = "cli-old", millis = 1))()
        repository.put(model(client = CliAgentUsageHistoryClient.MCP, sessionId = "mcp-old", millis = 1))()
        repository.put(model(client = CliAgentUsageHistoryClient.ENV, sessionId = "env-old", millis = 1))()
        repository.put(model(client = CliAgentUsageHistoryClient.CLI, sessionId = "cli-new", millis = 3))()

        repository.removeOlderThan(Instant.fromEpochMilliseconds(2))()

        val cliHistory = repository.getRecent(CliAgentUsageHistoryClient.CLI).first()
        assertEquals(listOf("cli-new"), cliHistory.map { it.sessionId })
        assertEquals(0L, repository.getCount(CliAgentUsageHistoryClient.MCP).first())
        assertEquals(0L, repository.getCount(CliAgentUsageHistoryClient.ENV).first())
    }

    @Test
    fun `put stores every field`() = runTest {
        val repository = createRepository(cipherIds = listOf("env-a"))
        val model = model(
            client = CliAgentUsageHistoryClient.CLI,
            cipherId = "env-a",
            millis = 7,
        ).copy(
            caller = "{}",
            clientName = "Client",
            response = CliAgentUsageHistoryResponseType.FAILURE,
            errorCode = "operation_failed",
            command = "npm run dev",
            path = "/home/user/project",
            eventId = "event",
        )
        repository.put(model)()

        val history = repository.getRecent(CliAgentUsageHistoryClient.CLI).first()

        assertEquals(listOf(model), history.map { it.copy(id = null) })
    }

    @Test
    fun `put ignores a replayed event`() = runTest {
        val repository = createRepository()
        val model = model(client = CliAgentUsageHistoryClient.ENV).copy(eventId = "event")
        repository.put(model)()
        repository.put(model)()

        assertEquals(1L, repository.getCount(CliAgentUsageHistoryClient.ENV).first())
    }

    private fun createRepository(cipherIds: List<String> = emptyList()): CliAgentUsageHistoryRepositoryImpl {
        val database = createUploadTestDatabase()
        if (cipherIds.isNotEmpty()) {
            database.insertUploadTestAccount()
            cipherIds.forEach { cipherId ->
                insertLocalCipher(database, testBitwardenCipher(cipherId = cipherId))
            }
        }
        return CliAgentUsageHistoryRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun model(
        client: CliAgentUsageHistoryClient,
        cipherId: String? = null,
        sessionId: String = "session",
        millis: Long = 1,
    ) = DCliAgentUsageHistory(
        cipherId = cipherId,
        sessionId = sessionId,
        client = client,
        caller = null,
        clientName = null,
        request = CliAgentUsageHistoryRequestType.RUN,
        response = CliAgentUsageHistoryResponseType.SUCCESS,
        errorCode = null,
        command = null,
        path = null,
        instant = Instant.fromEpochMilliseconds(millis),
    )
}
