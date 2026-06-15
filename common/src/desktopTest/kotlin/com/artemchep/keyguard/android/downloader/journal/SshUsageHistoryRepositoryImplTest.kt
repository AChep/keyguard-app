package com.artemchep.keyguard.android.downloader.journal

import com.artemchep.keyguard.common.model.DSshUsageHistory
import com.artemchep.keyguard.common.model.SshUsageHistoryRequestType
import com.artemchep.keyguard.common.model.SshUsageHistoryResponseType
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SshUsageHistoryRepositoryImplTest {
    @Test
    fun `get recent returns newest history first with limit`() = runTest {
        val repository = createRepository()
        val first = model(
            sessionId = "session-1",
            instant = Instant.fromEpochMilliseconds(1),
        )
        val second = model(
            sessionId = "session-2",
            instant = Instant.fromEpochMilliseconds(2),
        )
        val third = model(
            sessionId = "session-3",
            instant = Instant.fromEpochMilliseconds(3),
        )

        repository.put(first)()
        repository.put(second)()
        repository.put(third)()

        val history = repository.getRecent(limit = 2).first()

        assertEquals(listOf("session-3", "session-2"), history.map { it.sessionId })
    }

    @Test
    fun `get by cipher id filters and sorts history`() = runTest {
        val repository = createRepository()

        repository.put(
            model(
                cipherId = "cipher-a",
                sessionId = "session-a-1",
                instant = Instant.fromEpochMilliseconds(1),
            ),
        )()
        repository.put(
            model(
                cipherId = "cipher-b",
                sessionId = "session-b",
                instant = Instant.fromEpochMilliseconds(2),
            ),
        )()
        repository.put(
            model(
                cipherId = "cipher-a",
                sessionId = "session-a-2",
                instant = Instant.fromEpochMilliseconds(3),
            ),
        )()

        val history = repository.getByCipherId(cipherId = "cipher-a").first()

        assertEquals(listOf("session-a-2", "session-a-1"), history.map { it.sessionId })
    }

    @Test
    fun `get count returns total history count`() = runTest {
        val repository = createRepository()

        repository.put(model(sessionId = "session-1"))()
        repository.put(model(sessionId = "session-2"))()

        assertEquals(2L, repository.getCount().first())
    }

    @Test
    fun `remove all clears history`() = runTest {
        val repository = createRepository()

        repository.put(model(sessionId = "session-1"))()
        repository.put(model(sessionId = "session-2"))()
        repository.removeAll()()

        assertEquals(emptyList(), repository.getRecent().first())
        assertEquals(0L, repository.getCount().first())
    }

    @Test
    fun `get recent assigns distinct ids to identical events`() = runTest {
        val repository = createRepository()
        val instant = Instant.fromEpochMilliseconds(1)
        repository.put(model(sessionId = "session", instant = instant))()
        repository.put(model(sessionId = "session", instant = instant))()

        val history = repository.getRecent().first()

        assertEquals(2, history.size)
        assertEquals(2, history.mapNotNull { it.id }.toSet().size)
    }

    private fun createRepository(): SshUsageHistoryRepositoryImpl {
        val database = createUploadTestDatabase()
        return SshUsageHistoryRepositoryImpl(
            databaseManager = UploadTestVaultDatabaseManager(database),
            dispatcher = UnconfinedTestDispatcher(),
        )
    }

    private fun model(
        cipherId: String? = null,
        sessionId: String = "session",
        instant: Instant = Instant.fromEpochMilliseconds(1),
    ) = DSshUsageHistory(
        cipherId = cipherId,
        sessionId = sessionId,
        caller = null,
        request = SshUsageHistoryRequestType.AGENT_SIGN_DATA,
        response = SshUsageHistoryResponseType.SUCCESS,
        fingerprint = null,
        instant = instant,
    )
}
