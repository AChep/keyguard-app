package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.text.impl.Base64ServiceImpl
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.ServerHeader
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestMarkBackupAsDirty
import com.artemchep.keyguard.provider.bitwarden.sync.v2.UploadTestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.createUploadTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.insertUploadTestAccount
import com.artemchep.keyguard.provider.bitwarden.usecase.internal.SaveBitwardenAccountImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.util.getAndUpdateUserToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.KotlinxSerializationConverter
import io.ktor.http.ContentType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AccountClientCertificateTest {
    @Test
    fun `offline replacement and removal preserve all other account fields and isolate accounts`() = runTest {
        val fixture = Fixture()
        fixture.database.accountQueries.insert(accountId = "another", data = fixture.initial.copy(id = "another"))
        fixture.save(fixture.first, fixture.second)
        assertEquals(
            fixture.initial.copy(env = fixture.initial.env.copy(mutualTls = fixture.second)), fixture.current(),
        )
        val other = fixture.database.accountQueries.getByAccountId("another").executeAsOne().data_ as BitwardenToken
        assertEquals(fixture.first, other.env.mutualTls)
        fixture.save(fixture.second, null)
        assertEquals(fixture.initial.copy(env = fixture.initial.env.copy(mutualTls = null)), fixture.current())
    }

    @Test
    fun `stale editor and removed accounts cannot be overwritten or recreated`() = runTest {
        val fixture = Fixture()
        fixture.save(fixture.first, fixture.second)
        assertFailsWith<AccountCertificateConflictException> { fixture.save(fixture.first, null) }
        assertEquals(fixture.second, fixture.current().env.mutualTls)
        fixture.database.accountQueries.deleteByAccountId(fixture.initial.id)
        assertFailsWith<AccountCertificateUnavailableException> { fixture.save(fixture.second, null) }
        assertNull(fixture.database.accountQueries.getByAccountId(fixture.initial.id).executeAsOneOrNull())
    }

    @Test
    fun `refresh preserves certificate changed during the HTTP request`() = runTest {
        val fixture = Fixture()
        val refreshed = fixture.refresh { fixture.save(fixture.first, fixture.second) }
        assertEquals("refreshed", refreshed.token?.accessToken)
        assertEquals(fixture.second, refreshed.env.mutualTls)
        assertEquals(refreshed, fixture.current())
    }

    @Test
    fun `refresh preserves newer login credentials and never resurrects a removed account`() = runTest {
        val fixture = Fixture()
        val newer = fixture.initial.copy(token = fixture.initial.token!!.copy(accessToken = "new-login"))
        val refreshed = fixture.refresh { fixture.database.accountQueries.insert(accountId = newer.id, data = newer) }
        assertEquals(newer, refreshed)
        fixture.database.accountQueries.insert(accountId = fixture.initial.id, data = fixture.initial)
        assertFailsWith<IllegalStateException> {
            fixture.refresh { fixture.database.accountQueries.deleteByAccountId(fixture.initial.id) }
        }
        assertNull(fixture.database.accountQueries.getByAccountId(fixture.initial.id).executeAsOneOrNull())
    }

    @Test
    fun `reauthentication saves credentials into latest environment and fails after account removal`() = runTest {
        val fixture = Fixture()
        val staleLogin = fixture.initial.copy(token = fixture.initial.token!!.copy(accessToken = "new-login"))
        fixture.saveAccount(ioEffect {
            fixture.save(fixture.first, fixture.second)
            staleLogin
        }, fixture.initial.id).bind()
        assertEquals(staleLogin.copy(env = fixture.initial.env.copy(mutualTls = fixture.second)), fixture.current())
        fixture.database.accountQueries.deleteByAccountId(fixture.initial.id)
        assertFailsWith<IllegalStateException> { fixture.saveAccount(io(staleLogin), fixture.initial.id).bind() }
        assertNull(fixture.database.accountQueries.getByAccountId(fixture.initial.id).executeAsOneOrNull())
    }

    @Test
    fun `certificate changes reconnect only the affected notification worker`() = runTest {
        val fixture = Fixture()
        val workers = mutableListOf<Job>()
        val controller = NotificationsImpl.ActiveAccountsController(backgroundScope) {
            Job().also(workers::add)
        }
        val other = fixture.initial.copy(id = "another")
        controller.reconcile(listOf(fixture.initial, other))
        val replacement = fixture.initial.copy(env = fixture.initial.env.copy(mutualTls = fixture.second))
        controller.reconcile(listOf(replacement, other))
        assertTrue(workers[0].isCancelled)
        assertFalse(workers[1].isCancelled)
        assertEquals(3, workers.size)
        controller.reconcile(listOf(replacement.copy(env = replacement.env.copy(mutualTls = null)), other))
        assertTrue(workers[2].isCancelled)
        assertFalse(workers[1].isCancelled)
        workers.forEach { it.cancel() }
    }

    private class Fixture {
        val first = ServerEnv.MutualTls.ImportedPkcs12("first")
        val second = ServerEnv.MutualTls.ImportedPkcs12("second")
        val database = createUploadTestDatabase().apply { insertUploadTestAccount() }
        val manager = UploadTestVaultDatabaseManager(database)
        val initial = (database.accountQueries.get().executeAsOne().data_ as BitwardenToken).copy(
            env = BitwardenToken.Environment.of(ServerEnv(
                baseUrl = "https://vault.example.com",
                apiUrl = "https://api.example.com",
                headers = listOf(ServerHeader("X-Access", "header")),
                mutualTls = first,
            )),
            token = BitwardenToken.Token("refresh", "old", Instant.fromEpochSeconds(0)),
        ).also { database.accountQueries.insert(accountId = it.id, data = it) }
        // Sync is intentionally unavailable: saves must not perform a network request.
        private val queue = object : QueueSyncById {
            override fun invoke(accountId: AccountId) = io(Unit)
        }
        private val scope = object : WindowCoroutineScope {
            override val coroutineContext = Job().apply { cancel() }
        }
        val save = PutAccountMutualTls(manager, UploadTestMarkBackupAsDirty, queue, scope)
        val saveAccount = SaveBitwardenAccountImpl(
            getPurchased = object : GetPurchased { override fun invoke() = flowOf(Premium.INCLUDED) },
            getAccounts = object : GetAccounts {
                override fun invoke() = flowOf(emptyList<com.artemchep.keyguard.common.model.DAccount>())
            },
            queueSyncById = queue,
            windowCoroutineScope = scope,
            logRepository = LogRepositoryBridge(emptyList()),
            db = manager,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
        )
        fun current() = database.accountQueries.getByAccountId(initial.id).executeAsOne().data_ as BitwardenToken
        suspend fun save(expected: ServerEnv.MutualTls?, replacement: ServerEnv.MutualTls?) =
            save(AccountId(initial.id), expected, replacement).bind()

        suspend fun refresh(duringRequest: suspend () -> Unit): BitwardenToken {
            val client = HttpClient(MockEngine {
                duringRequest()
                respond(
                    """{"access_token":"refreshed","refresh_token":"new-refresh",
                        "token_type":"Bearer","expires_in":3600}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }) {
                install(ContentNegotiation) {
                    register(ContentType.Application.Json, KotlinxSerializationConverter(Json))
                }
            }
            return try { getAndUpdateUserToken(Base64ServiceImpl(), client, Json, manager, initial) }
            finally { client.close() }
        }
    }
}
