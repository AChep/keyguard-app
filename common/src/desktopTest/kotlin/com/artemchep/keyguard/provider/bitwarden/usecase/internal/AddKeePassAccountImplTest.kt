package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.exception.PremiumException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.model.DAccount
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.common.service.keepass.FakeKeePassWebDavClientFactory
import com.artemchep.keyguard.common.service.webdav.WebDavClientFactory
import com.artemchep.keyguard.common.service.keepass.prepareKeePassDatabase
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.QueueSyncById
import com.artemchep.keyguard.common.usecase.SyncById
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.core.store.bitwarden.FileLocation
import com.artemchep.keyguard.core.store.bitwarden.KeePassToken
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.TestLogRepository
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.TestVaultDatabaseManager
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.createTestDatabase
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testBase64Service
import com.artemchep.keyguard.provider.bitwarden.sync.v2.keepass.testCryptoGenerator
import java.nio.file.Files
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AddKeePassAccountImplTest {
    private val fileService = FileServiceImpl()

    @Test
    fun `queued add stores local token and queues sync`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            val keyData = "key-file-data".encodeToByteArray()
            val keyUri = dir.resolve("vault.key").apply {
                writeBytes(keyData)
            }.toUri().toString()
            val fixture = createFixture(db = db, scope = testScope)

            val accountId = fixture.useCase(
                params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    dbFileName = "custom-vault.kdbx",
                    keyUri = keyUri,
                    password = "secret",
                    managedByApp = true,
                    syncMode = AddKeePassAccountParams.SyncMode.Queued,
                ),
            ).bind()
            advanceUntilIdle()

            val token = db.accountQueries
                .getByAccountId(accountId.id)
                .executeAsOne()
                .data_
            val keePassToken = assertIs<KeePassToken>(token)
            assertEquals("secret", testBase64Service.decodeToString(keePassToken.key.passwordBase64))
            assertContentEquals(keyData, testBase64Service.decode(keePassToken.key.keyBase64!!))
            assertEquals("custom-vault.kdbx", keePassToken.database.fileName)
            val location = assertIs<FileLocation.Local>(keePassToken.database.location)
            assertEquals(dbUri, location.uri)
            assertEquals(true, location.managedByApp)
            assertEquals(listOf(accountId), fixture.queuedSyncs)
            assertEquals(emptyList(), fixture.directSyncs)
        }
    }

    @Test
    fun `open with wrong password fails before inserting account or scheduling sync`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val dbUri = dir.resolve("vault.kdbx").toUri().toString()
            prepareKeePassDatabase(
                fileService = fileService,
                params = params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dbUri,
                    password = "correct",
                ),
            )
            val db = createTestDatabase()
            val fixture = createFixture(db = db, scope = testScope)

            assertFailsWith<Throwable> {
                fixture.useCase(
                    params(
                        mode = AddKeePassAccountParams.Mode.Open,
                        dbUri = dbUri,
                        password = "wrong",
                        syncMode = AddKeePassAccountParams.SyncMode.Queued,
                    ),
                ).bind()
            }
            advanceUntilIdle()

            assertTrue(db.accountQueries.get().executeAsList().isEmpty())
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(emptyList(), fixture.directSyncs)
        }
    }

    @Test
    fun `open with missing database fails before inserting account or direct sync`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val fixture = createFixture(db = db, scope = testScope)

            assertFailsWith<Throwable> {
                fixture.useCase(
                    params(
                        mode = AddKeePassAccountParams.Mode.Open,
                        dbUri = dir.resolve("missing.kdbx").toUri().toString(),
                        password = "secret",
                        syncMode = AddKeePassAccountParams.SyncMode.Direct,
                    ),
                ).bind()
            }
            advanceUntilIdle()

            assertTrue(db.accountQueries.get().executeAsList().isEmpty())
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(emptyList(), fixture.directSyncs)
        }
    }

    @Test
    fun `missing key file fails before inserting account or scheduling sync`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val fixture = createFixture(db = db, scope = testScope)

            assertFailsWith<Throwable> {
                fixture.useCase(
                    params(
                        mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                        dbUri = dir.resolve("vault.kdbx").toUri().toString(),
                        keyUri = dir.resolve("missing.key").toUri().toString(),
                        password = "secret",
                        syncMode = AddKeePassAccountParams.SyncMode.Queued,
                    ),
                ).bind()
            }
            advanceUntilIdle()

            assertTrue(db.accountQueries.get().executeAsList().isEmpty())
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(emptyList(), fixture.directSyncs)
            assertFalse(Files.exists(dir.resolve("vault.kdbx")))
        }
    }

    @Test
    fun `queued add stores webdav username and password in token`() = runTest {
        val testScope = this
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val db = createTestDatabase()
        val fixture = createFixture(
            db = db,
            scope = testScope,
            webDavClientFactory = webDavClientFactory,
        )

        val dbUri = "https://example.com/dav/vault.kdbx"
        val accountId = fixture.useCase(
            params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(
                    url = dbUri,
                    username = "alice",
                    password = "server-password",
                ),
                syncMode = AddKeePassAccountParams.SyncMode.Queued,
            ),
        ).bind()
        advanceUntilIdle()

        val token = db.accountQueries
            .getByAccountId(accountId.id)
            .executeAsOne()
            .data_
        val keePassToken = assertIs<KeePassToken>(token)
        val location = assertIs<FileLocation.WebDav>(keePassToken.database.location)
        assertEquals("alice", location.username)
        assertEquals("server-password", location.password?.value)
        assertEquals(listOf(accountId), fixture.queuedSyncs)
    }

    @Test
    fun `queued add stores empty webdav password as null`() = runTest {
        val testScope = this
        val webDavClientFactory = FakeKeePassWebDavClientFactory()
        val db = createTestDatabase()
        val fixture = createFixture(
            db = db,
            scope = testScope,
            webDavClientFactory = webDavClientFactory,
        )

        val dbUri = "https://example.com/dav/vault.kdbx"
        val accountId = fixture.useCase(
            params(
                mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                dbUri = dbUri,
                password = "secret",
                webDav = webDavFile(
                    url = dbUri,
                    username = "alice",
                    password = "",
                ),
                syncMode = AddKeePassAccountParams.SyncMode.Queued,
            ),
        ).bind()
        advanceUntilIdle()

        val token = db.accountQueries
            .getByAccountId(accountId.id)
            .executeAsOne()
            .data_
        val keePassToken = assertIs<KeePassToken>(token)
        val location = assertIs<FileLocation.WebDav>(keePassToken.database.location)
        assertEquals("alice", location.username)
        assertNull(location.password)
        assertEquals(listOf(accountId), fixture.queuedSyncs)
    }

    @Test
    fun `direct add calls and awaits syncById`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val fixture = createFixture(db = db, scope = testScope)

            val accountId = fixture.useCase(
                params(
                    mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                    dbUri = dir.resolve("vault.kdbx").toUri().toString(),
                    password = "secret",
                    syncMode = AddKeePassAccountParams.SyncMode.Direct,
                ),
            ).bind()

            assertEquals(listOf(accountId), fixture.directSyncs)
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(accountId.id, db.accountQueries.get().executeAsList().single().accountId)
        }
    }

    @Test
    fun `direct add propagates sync failure`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val fixture = createFixture(
                db = db,
                scope = testScope,
                syncFailure = IllegalStateException("sync failed"),
            )

            val error = assertFailsWith<IllegalStateException> {
                fixture.useCase(
                    params(
                        mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                        dbUri = dir.resolve("vault.kdbx").toUri().toString(),
                        password = "secret",
                        syncMode = AddKeePassAccountParams.SyncMode.Direct,
                    ),
                ).bind()
            }

            assertEquals("sync failed", error.message)
            assertEquals(1, fixture.directSyncs.size)
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(1, db.accountQueries.get().executeAsList().size)
        }
    }

    @Test
    fun `premium denial does not insert account or schedule sync`() = runTest {
        val testScope = this
        withTempDir { dir ->
            val db = createTestDatabase()
            val dbPath = dir.resolve("vault.kdbx")
            val fixture = createFixture(
                db = db,
                scope = testScope,
                purchased = false,
                accounts = listOf(
                    account("existing-1"),
                    account("existing-2"),
                ),
            )

            assertFailsWith<PremiumException> {
                fixture.useCase(
                    params(
                        mode = AddKeePassAccountParams.Mode.New(allowOverwrite = false),
                        dbUri = dbPath.toUri().toString(),
                        password = "secret",
                        syncMode = AddKeePassAccountParams.SyncMode.Queued,
                    ),
                ).bind()
            }
            advanceUntilIdle()

            assertTrue(db.accountQueries.get().executeAsList().isEmpty())
            assertEquals(emptyList(), fixture.queuedSyncs)
            assertEquals(emptyList(), fixture.directSyncs)
            assertFalse(Files.exists(dbPath))
        }
    }

    private fun createFixture(
        db: com.artemchep.keyguard.data.Database,
        scope: CoroutineScope,
        purchased: Boolean = true,
        accounts: List<DAccount> = emptyList(),
        syncFailure: Throwable? = null,
        webDavClientFactory: WebDavClientFactory = FakeKeePassWebDavClientFactory(),
    ): Fixture {
        val queuedSyncs = mutableListOf<AccountId>()
        val directSyncs = mutableListOf<AccountId>()
        val queueSyncById = object : QueueSyncById {
            override fun invoke(accountId: AccountId): IO<Unit> = ioEffect {
                queuedSyncs += accountId
            }
        }
        val syncById = object : SyncById {
            override fun invoke(accountId: AccountId): IO<Boolean> = ioEffect {
                directSyncs += accountId
                syncFailure?.let { throw it }
                true
            }
        }
        val getPurchased = object : GetPurchased {
            override fun invoke() = flowOf(purchased)
        }
        val getAccounts = object : GetAccounts {
            override fun invoke() = flowOf(accounts)
        }
        val useCase = AddKeePassAccountImpl(
            getPurchased = getPurchased,
            getAccounts = getAccounts,
            queueSyncById = queueSyncById,
            syncById = syncById,
            windowCoroutineScope = TestWindowCoroutineScope(scope),
            logRepository = TestLogRepository,
            cryptoGenerator = testCryptoGenerator,
            fileService = fileService,
            base64Service = testBase64Service,
            webDavClientFactory = webDavClientFactory,
            db = TestVaultDatabaseManager(db),
        )
        return Fixture(
            useCase = useCase,
            queuedSyncs = queuedSyncs,
            directSyncs = directSyncs,
        )
    }

    private data class Fixture(
        val useCase: AddKeePassAccountImpl,
        val queuedSyncs: List<AccountId>,
        val directSyncs: List<AccountId>,
    )

    private class TestWindowCoroutineScope(
        scope: CoroutineScope,
    ) : WindowCoroutineScope, CoroutineScope by scope

    private fun params(
        mode: AddKeePassAccountParams.Mode,
        dbUri: String,
        password: String,
        dbFileName: String = "vault.kdbx",
        managedByApp: Boolean = false,
        keyUri: String? = null,
        webDav: WebDavLocation.File? = null,
        syncMode: AddKeePassAccountParams.SyncMode = AddKeePassAccountParams.SyncMode.Queued,
    ) = AddKeePassAccountParams(
        mode = mode,
        dbUri = dbUri,
        dbFileName = dbFileName,
        managedByApp = managedByApp,
        webDav = webDav,
        keyUri = keyUri,
        password = password,
        syncMode = syncMode,
    )

    private fun webDavFile(
        url: String,
        username: String? = null,
        password: String? = null,
    ) = WebDavLocation.File(
        url = url,
        credentials = WebDavCredentials.of(
            username = username,
            password = password,
        ),
    )

    private fun account(
        id: String,
    ) = DAccount(
        id = AccountId(id),
        username = null,
        host = "local",
        webVaultUrl = null,
        localVaultUrl = null,
        type = AccountType.KEEPASS,
        faviconServer = null,
    )

    private inline fun withTempDir(
        block: (java.nio.file.Path) -> Unit,
    ) {
        val dir = Files.createTempDirectory("keyguard-add-keepass-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
