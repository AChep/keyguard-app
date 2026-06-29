package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.canRetry
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.revisionDate
import com.artemchep.keyguard.provider.bitwarden.api.builder.sync
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.FolderRequest
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2TestServer.Companion.INSTANT_0
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2TestServer.Companion.INSTANT_1
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2TestServer.Companion.INSTANT_2
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2TestServer.Companion.INSTANT_4
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2TestServer.TestHttpFailure
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.fetchServerRevisionDateOrNull
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.requiresAuthenticationForSyncFailure
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.shouldSkipFullSyncForRevision
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.requireCleanForRevisionCache
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.strategy.FolderSyncStrategy
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SyncV2PipelineIntegrationTest {
    @Test
    fun `fresh sync downloads remote folders through Bitwarden sync endpoint`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder(
            id = "folder-remote-1",
            name = "Remote folder",
            revisionDate = INSTANT_1,
        )
        server.revisionDate = "rev-1"
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)

        val result = runner.run()

        assertEquals(false, result.skipped)
        assertEquals(1, result.executionResult?.succeeded)
        assertEquals(listOf("Remote folder"), store.locals.values.map { it.name })
        val local = store.locals.values.single()
        assertEquals("folder-remote-1", local.service.remote?.id)
        assertEquals(INSTANT_1, local.service.remote?.revisionDate)
        assertEquals(INSTANT_4, runner.meta?.lastSyncTimestamp)
        assertEquals("rev-1", runner.meta?.lastServerRevisionDate)
        assertEquals(BitwardenMeta.LastSyncResult.Success, runner.meta?.lastSyncResult)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
        assertTrue(server.requests.all { it.authorization == "Bearer ${server.token}" })
    }

    @Test
    fun `sync 429 response retries immediately and honors Retry-After`() = runTest {
        val server = BitwardenSyncV2TestServer(installRetryPolicy = true)
        server.seedFolder(
            id = "folder-remote-1",
            name = "Remote folder",
            revisionDate = INSTANT_1,
        )
        server.revisionDate = "rev-1"
        server.syncFailures.add(
            TestHttpFailure(
                status = HttpStatusCode.TooManyRequests,
                body = """{"message":"rate limited"}""",
                headers = mapOf(HttpHeaders.RetryAfter to "3"),
            ),
        )
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)

        val result = runner.run()

        assertEquals(false, result.skipped)
        assertEquals(1, result.executionResult?.succeeded)
        assertEquals(listOf("Remote folder"), store.locals.values.map { it.name })
        assertEquals(listOf(3_000L), server.retryDelays)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
        assertEquals(BitwardenMeta.LastSyncResult.Success, runner.meta?.lastSyncResult)
    }

    @Test
    fun `repeated clean sync skips full sync when revision is unchanged`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_1)
        server.revisionDate = "rev-stable"
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)

        runner.run()
        server.clearRequests()
        val skipped = runner.run()

        assertEquals(true, skipped.skipped)
        assertEquals(null, skipped.executionResult)
        assertEquals(listOf("Remote folder"), store.locals.values.map { it.name })
        assertEquals(
            listOf(HttpMethod.Get to "/api/accounts/revision-date"),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `unchanged revision does not skip local-only upload`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.revisionDate = "rev-stable"
        val store = FolderIntegrationStore()
        store.addLocalOnly("local-folder-1", "Local folder")
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertEquals(false, result.skipped)
        assertEquals(1, result.executionResult?.succeeded)
        assertEquals(listOf("Local folder"), server.folders.values.map { it.name })
        val local = store.locals.getValue("local-folder-1")
        assertEquals("folder-created-1", local.service.remote?.id)
        assertEquals(INSTANT_4, local.service.remote?.revisionDate)
        assertNull(local.service.error)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
                HttpMethod.Post to "/api/folders/",
            ),
            server.requests.map { it.method to it.path },
        )
        assertEquals(
            """{"name":"Local folder"}""",
            server.requests.single { it.method == HttpMethod.Post }.body,
        )
    }

    @Test
    fun `local folder edit uploads with official PUT and updates local remote metadata`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Old remote name", INSTANT_0)
        server.revisionDate = "rev-stable"
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Local rename",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertEquals(1, result.executionResult?.succeeded)
        assertEquals("Local rename", server.folders.getValue("folder-remote-1").name)
        val local = store.locals.getValue("local-folder-1")
        assertEquals("Local rename", local.name)
        assertEquals(INSTANT_4, local.revisionDate)
        assertEquals(INSTANT_4, local.service.remote?.revisionDate)
        assertEquals(
            """{"name":"Local rename"}""",
            server.requests.single { it.method == HttpMethod.Put }.body,
        )
    }

    @Test
    fun `folder upload 429 response retries immediately and completes cleanly`() = runTest {
        val server = BitwardenSyncV2TestServer(installRetryPolicy = true)
        server.seedFolder("folder-remote-1", "Old remote name", INSTANT_0)
        server.revisionDate = "rev-stable"
        server.folderPutFailures.add(
            TestHttpFailure(
                status = HttpStatusCode.TooManyRequests,
                body = """{"message":"rate limited"}""",
                headers = mapOf(HttpHeaders.RetryAfter to "2"),
            ),
        )
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Local rename",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertEquals(1, result.executionResult?.succeeded)
        assertEquals("Local rename", server.folders.getValue("folder-remote-1").name)
        val local = store.locals.getValue("local-folder-1")
        assertEquals("Local rename", local.name)
        assertNull(local.service.error)
        assertEquals(listOf(2_000L), server.retryDelays)
        assertEquals(2, server.requests.count { it.method == HttpMethod.Put })
        assertEquals(BitwardenMeta.LastSyncResult.Success, runner.meta?.lastSyncResult)
    }

    @Test
    fun `remote folder update downloads through full sync without uploading`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote rename", INSTANT_2)
        server.revisionDate = "rev-remote-update"
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Old local name",
            localRevisionDate = INSTANT_0,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-old")

        val result = runner.run()

        assertEquals(1, result.executionResult?.succeeded)
        val local = store.locals.getValue("local-folder-1")
        assertEquals("Remote rename", local.name)
        assertEquals(INSTANT_2, local.revisionDate)
        assertEquals(INSTANT_2, local.service.remote?.revisionDate)
        assertEquals(emptyList(), server.requests.filter { it.method == HttpMethod.Put })
    }

    @Test
    fun `stale cached sync response does not overwrite newer local folder or cache revision`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "New server name", INSTANT_2)
        server.syncFoldersOverride =
            listOf(
                FolderEntity(
                    id = "folder-remote-1",
                    name = "Cached old name",
                    revisionDate = INSTANT_1,
                ),
            )
        server.revisionDate = "rev-after-newer-write"
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "New server name",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_2,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-before-stale-cache")

        val result = runner.run()

        assertNull(result.failure)
        assertEquals(0, result.executionResult?.succeeded)
        assertEquals(0, result.executionResult?.skipped)
        assertEquals(1, result.executionResult?.staleServerEntities)
        assertEquals("rev-before-stale-cache", runner.meta?.lastServerRevisionDate)
        assertEquals(BitwardenMeta.LastSyncResult.Success, runner.meta?.lastSyncResult)
        assertEquals("New server name", server.folders.getValue("folder-remote-1").name)
        val local = store.locals.getValue("local-folder-1")
        assertEquals("New server name", local.name)
        assertEquals(INSTANT_2, local.revisionDate)
        assertEquals(INSTANT_2, local.service.remote?.revisionDate)
        assertEquals(emptyList(), server.requests.filter { it.method == HttpMethod.Put })
    }

    @Test
    fun `pending local folder deletion sends official DELETE and removes local live record`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_0)
        server.revisionDate = "rev-stable"
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Remote folder",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
            locallyDeleted = true,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertEquals(1, result.executionResult?.succeeded)
        assertEquals(emptyMap(), server.folders)
        assertEquals(emptyMap(), store.locals)
        assertEquals(
            HttpMethod.Delete to "/api/folders/folder-remote-1",
            server.requests.last().method to server.requests.last().path,
        )
    }

    @Test
    fun `server validation error records item error and does not advance cached revision`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_0)
        server.revisionDate = "rev-stable"
        server.nextFolderPutFailure = TestHttpFailure(
            status = HttpStatusCode.BadRequest,
            body = """{"error":{"description":"Folder name is invalid"}}""",
        )
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Invalid local name",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals("rev-stable", runner.meta?.lastServerRevisionDate)
        assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        val local = store.locals.getValue("local-folder-1")
        assertEquals("Invalid local name", local.name)
        assertEquals(400, local.service.error?.code)
        assertEquals("Folder name is invalid", local.service.error?.message)
        assertEquals("Remote folder", server.folders.getValue("folder-remote-1").name)
    }

    @Test
    fun `folder upload auth failure records account reauthentication after action failure wrapping`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_0)
        server.revisionDate = "rev-stable"
        server.nextFolderPutFailure = TestHttpFailure(
            status = HttpStatusCode.Forbidden,
            body = """{"error":{"description":"forbidden"}}""",
        )
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Local rename",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable")

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals(1, result.executionResult?.failures?.size)
        assertEquals("rev-stable", runner.meta?.lastServerRevisionDate)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(true, lastSyncResult.requiresAuthentication)
        val local = store.locals.getValue("local-folder-1")
        assertEquals(403, local.service.error?.code)
        assertEquals("Remote folder", server.folders.getValue("folder-remote-1").name)
    }

    @Test
    fun `folder upload 429 exhaustion records retryable item failure metadata`() = runTest {
        val server = BitwardenSyncV2TestServer(installRetryPolicy = true)
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_0)
        server.revisionDate = "rev-stable"
        repeat(6) {
            server.folderPutFailures.add(
                TestHttpFailure(
                    status = HttpStatusCode.TooManyRequests,
                    body = """{"message":"rate limited"}""",
                    headers = mapOf(HttpHeaders.RetryAfter to "4"),
                ),
            )
        }
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Local rename",
            localRevisionDate = INSTANT_2,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-stable").copy(lastSyncTimestamp = INSTANT_1)

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals(1, result.executionResult?.failures?.size)
        assertEquals(INSTANT_1, runner.meta?.lastSyncTimestamp)
        assertEquals("rev-stable", runner.meta?.lastServerRevisionDate)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(false, lastSyncResult.requiresAuthentication)
        assertEquals(INSTANT_4, lastSyncResult.timestamp)
        assertEquals(List(5) { 4_000L }, server.retryDelays)
        assertEquals(6, server.requests.count { it.method == HttpMethod.Put })
        val local = store.locals.getValue("local-folder-1")
        val error = assertNotNull(local.service.error)
        assertEquals(429, error.code)
        assertTrue(error.message?.contains("rate limited") == true)
        assertEquals(true, error.canRetry(local.revisionDate))
        assertEquals("Remote folder", server.folders.getValue("folder-remote-1").name)
    }

    @Test
    fun `revision endpoint failure falls back to full sync and leaves cached revision unchanged`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.seedFolder("folder-remote-1", "Remote folder", INSTANT_1)
        server.revisionDateFailure = TestHttpFailure(
            status = HttpStatusCode.ServiceUnavailable,
            body = """{"message":"temporarily unavailable"}""",
        )
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-old")

        val result = runner.run()

        assertEquals(false, result.skipped)
        assertEquals(1, result.executionResult?.succeeded)
        assertEquals("rev-old", runner.meta?.lastServerRevisionDate)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `sync HTTP auth failure is classified as account reauthentication without mutating local data`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.syncFailure = TestHttpFailure(
            status = HttpStatusCode.Unauthorized,
            body = """{"error":{"description":"invalid token"}}""",
        )
        val store = FolderIntegrationStore()
        store.addSynced(
            localId = "local-folder-1",
            remoteId = "folder-remote-1",
            name = "Existing local folder",
            localRevisionDate = INSTANT_0,
            remoteRevisionDate = INSTANT_0,
        )
        val runner = FolderSyncV2IntegrationRunner(server, store)

        val result = runner.run()

        assertNotNull(result.failure)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(true, lastSyncResult.requiresAuthentication)
        assertEquals("Existing local folder", store.locals.getValue("local-folder-1").name)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `sync forbidden failure records authentication-required metadata and preserves previous sync fields`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.syncFailure = TestHttpFailure(
            status = HttpStatusCode.Forbidden,
            body = """{"error":{"description":"forbidden"}}""",
        )
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-old").copy(lastSyncTimestamp = INSTANT_1)

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals(INSTANT_1, runner.meta?.lastSyncTimestamp)
        assertEquals("rev-old", runner.meta?.lastServerRevisionDate)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(true, lastSyncResult.requiresAuthentication)
        assertEquals(INSTANT_4, lastSyncResult.timestamp)
    }

    @Test
    fun `sync server failure records retryable failure metadata and preserves previous sync fields`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.syncFailure = TestHttpFailure(
            status = HttpStatusCode.InternalServerError,
            body = """{"error":{"description":"server failed"}}""",
        )
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-old").copy(lastSyncTimestamp = INSTANT_1)

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals(INSTANT_1, runner.meta?.lastSyncTimestamp)
        assertEquals("rev-old", runner.meta?.lastServerRevisionDate)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(false, lastSyncResult.requiresAuthentication)
        assertEquals(INSTANT_4, lastSyncResult.timestamp)
    }

    @Test
    fun `sync non HTTP failure records retryable failure metadata and preserves previous sync fields`() = runTest {
        val server = BitwardenSyncV2TestServer()
        server.syncException = IOException("network down")
        val store = FolderIntegrationStore()
        val runner = FolderSyncV2IntegrationRunner(server, store)
        runner.meta = cleanMeta("rev-old").copy(lastSyncTimestamp = INSTANT_1)

        val result = runner.run()

        assertNotNull(result.failure)
        assertEquals(INSTANT_1, runner.meta?.lastSyncTimestamp)
        assertEquals("rev-old", runner.meta?.lastServerRevisionDate)
        val lastSyncResult = assertIs<BitwardenMeta.LastSyncResult.Failure>(runner.meta?.lastSyncResult)
        assertEquals(false, lastSyncResult.requiresAuthentication)
        assertEquals(INSTANT_4, lastSyncResult.timestamp)
    }

    private fun cleanMeta(revisionDate: String) = BitwardenMeta(
        accountId = ACCOUNT_ID,
        lastSyncResult = BitwardenMeta.LastSyncResult.Success,
        lastServerRevisionDate = revisionDate,
        lastSyncServiceVersion = BitwardenService.VERSION,
    )
}

private class FolderSyncV2IntegrationRunner(
    private val server: BitwardenSyncV2TestServer,
    private val store: FolderIntegrationStore,
) {
    var meta: BitwardenMeta? = null

    suspend fun run(): FolderSyncV2IntegrationResult {
        val api = server.env.api
        val serverRevisionDate =
            fetchServerRevisionDateOrNull {
                api.accounts.revisionDate(
                    httpClient = server.client,
                    env = server.env,
                    token = server.token,
                )
            }

        if (
            shouldSkipFullSyncForRevision(
                existingMeta = meta,
                serverRevisionDate = serverRevisionDate,
                hasPendingLocalWork = store.hasPendingLocalWork(),
            )
        ) {
            return FolderSyncV2IntegrationResult(skipped = true)
        }

        var executionResult: SyncExecutionResult? = null
        return try {
            val response =
                api.sync(
                    httpClient = server.client,
                    env = server.env,
                    token = server.token,
                )
            val outcome =
                SyncCoordinator().safeSyncEntityType(
                    EntitySyncConfig(
                        name = "folders",
                        strategy = FolderSyncStrategy(),
                        localEntities = store.locals.values.toList(),
                        serverEntities = response.folders.orEmpty(),
                        ops =
                            FolderIntegrationOps(
                                apiServer = server,
                                store = store,
                            ),
                    ),
                )
            executionResult =
                (outcome as? EntityTypeOutcome.Completed)
                    ?.result
            val syncResult = SyncResult(mapOf("folders" to outcome))
            syncResult.requireCleanForRevisionCache()
            if (serverRevisionDate != null && syncResult.canCacheServerRevisionDate) {
                meta =
                    (meta ?: BitwardenMeta(accountId = ACCOUNT_ID)).copy(
                        lastServerRevisionDate = serverRevisionDate,
                        lastSyncServiceVersion = BitwardenService.VERSION,
                    )
            }
            meta =
                (meta ?: BitwardenMeta(accountId = ACCOUNT_ID)).copy(
                    lastSyncTimestamp = INSTANT_4,
                    lastSyncResult = BitwardenMeta.LastSyncResult.Success,
                )
            FolderSyncV2IntegrationResult(
                skipped = false,
                executionResult = executionResult,
            )
        } catch (e: Throwable) {
            meta =
                (meta ?: BitwardenMeta(accountId = ACCOUNT_ID)).copy(
                    lastSyncResult =
                        BitwardenMeta.LastSyncResult.Failure(
                            timestamp = INSTANT_4,
                            reason = e.message,
                            requiresAuthentication = requiresAuthenticationForSyncFailure(e),
                        ),
                )
            FolderSyncV2IntegrationResult(
                skipped = false,
                executionResult = executionResult,
                failure = e,
            )
        }
    }
}

private data class FolderSyncV2IntegrationResult(
    val skipped: Boolean,
    val executionResult: SyncExecutionResult? = null,
    val failure: Throwable? = null,
)

private class FolderIntegrationStore {
    val locals = linkedMapOf<String, BitwardenFolder>()

    fun addLocalOnly(
        localId: String,
        name: String,
    ) {
        locals[localId] =
            BitwardenFolder(
                accountId = ACCOUNT_ID,
                folderId = localId,
                revisionDate = INSTANT_2,
                service = BitwardenService(version = BitwardenService.VERSION),
                name = name,
            )
    }

    fun addSynced(
        localId: String,
        remoteId: String,
        name: String,
        localRevisionDate: kotlin.time.Instant,
        remoteRevisionDate: kotlin.time.Instant,
        locallyDeleted: Boolean = false,
    ) {
        locals[localId] =
            BitwardenFolder(
                accountId = ACCOUNT_ID,
                folderId = localId,
                revisionDate = localRevisionDate,
                service =
                    BitwardenService(
                        remote =
                            BitwardenService.Remote(
                                id = remoteId,
                                revisionDate = remoteRevisionDate,
                                deletedDate = null,
                            ),
                        deleted = locallyDeleted,
                        version = BitwardenService.VERSION,
                    ),
                name = name,
            )
    }

    fun hasPendingLocalWork(): Boolean {
        val strategy = FolderSyncStrategy()
        return locals.values.any { folder ->
            val meta = strategy.toLocalItemMeta(folder)
            meta.isLocallyDeleted ||
                meta.remoteId == null ||
                meta.hasError && meta.canRetryError ||
                meta.revisionDate != meta.lastSyncedRevisionDate ||
                meta.deletedDate != meta.lastSyncedDeletedDate
        }
    }
}

private class FolderIntegrationOps(
    private val apiServer: BitwardenSyncV2TestServer,
    private val store: FolderIntegrationStore,
) : EntitySyncOps<BitwardenFolder, FolderEntity> {
    override suspend fun readLocal(localId: String): BitwardenFolder? =
        store.locals[localId]

    override suspend fun insertOrUpdateLocally(entries: List<Pair<FolderEntity, BitwardenFolder?>>) {
        entries.forEach { (serverFolder, localFolder) ->
            val localId = localFolder?.folderId ?: "local-${serverFolder.id}"
            store.locals[localId] =
                BitwardenFolder
                    .encrypted(
                        accountId = ACCOUNT_ID,
                        folderId = localId,
                        entity = serverFolder,
                    )
        }
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<FolderEntity, BitwardenFolder>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        entries.forEach { entry ->
            val current = store.locals[entry.localId]
            if (entry.shouldUpdate(current)) {
                store.locals[entry.localId] =
                    BitwardenFolder
                        .encrypted(
                            accountId = ACCOUNT_ID,
                            folderId = entry.initialLocal.folderId,
                            entity = entry.server,
                        )
                applied++
            } else {
                skipped++
            }
        }
        return LocalUpdateResult(
            applied = applied,
            skipped = skipped,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        localIds.forEach(store.locals::remove)
    }

    override suspend fun saveLocal(
        local: BitwardenFolder,
        previousLocal: BitwardenFolder?,
    ) {
        store.locals[local.folderId] = local
    }

    override suspend fun pushToServer(
        local: BitwardenFolder,
        server: FolderEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenFolder> {
        val response =
            if (server == null) {
                apiServer.env.api.folders.post(
                    httpClient = apiServer.client,
                    env = apiServer.env,
                    token = apiServer.token,
                    body = FolderRequest(name = local.name),
                )
            } else {
                apiServer.env.api.folders.put(
                    httpClient = apiServer.client,
                    env = apiServer.env,
                    token = apiServer.token,
                    id = server.id,
                    body = FolderRequest(name = local.name),
                )
            }
        val decoded =
            BitwardenFolder
                .encrypted(
                    accountId = ACCOUNT_ID,
                    folderId = local.folderId,
                    entity = response,
                )
        return RemoteWriteOutcome.Upsert(decoded)
    }

    override suspend fun deleteOnServer(
        local: BitwardenFolder,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenFolder> {
        apiServer.env.api.folders.delete(
            httpClient = apiServer.client,
            env = apiServer.env,
            token = apiServer.token,
            id = serverId,
        )
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenFolder,
        server: FolderEntity,
    ): RemoteWriteOutcome<BitwardenFolder> =
        RemoteWriteOutcome.Upsert(
            BitwardenFolder
                .encrypted(
                    accountId = ACCOUNT_ID,
                    folderId = local.folderId,
                    entity = server,
                ),
        )
}
