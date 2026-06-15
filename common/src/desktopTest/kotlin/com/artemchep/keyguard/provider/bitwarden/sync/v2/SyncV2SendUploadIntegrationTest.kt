package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingSendFileUpload
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.getFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.api.builder.postFileV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadSendFile
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.appendProfileToken2
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendFileEntity
import com.artemchep.keyguard.provider.bitwarden.entity.SendTypeEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.of
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.SendSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.buildSendCodecPair
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.SendSyncStrategy
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

private suspend fun assertSendFailure(
    block: suspend () -> RemoteWriteOutcome<BitwardenSend>,
): RemoteWriteOutcome.Failure<BitwardenSend> =
    assertIs<RemoteWriteOutcome.Failure<BitwardenSend>>(block())

private val terminalSendFileReservationMessages =
    listOf(
        "Invalid content.",
        "Invalid content. File size hint is required.",
        "Max file size is 500 MB.",
        "File metadata is required for file sends.",
        "Email verified Sends require a premium membership",
        "Send is not of type \"file\".",
        "No file data.",
        "Not enough storage available.",
        "Due to an Enterprise Policy, you are only able to delete an existing Send.",
        "Due to an Enterprise Policy, you are not allowed to hide your email address from recipients " +
            "when creating or editing a Send.",
        "You must have premium status to use file Sends.",
        "You must confirm your email to use file Sends.",
        "This organization cannot use file sends.",
        "You cannot create a Send that is already expired. Adjust the expiration date and try again.",
        "You cannot have a Send with a deletion date in the past. Adjust the deletion date and try again.",
        "You cannot have a Send with a deletion date that far into the future. " +
            "Adjust the Deletion Date to a value less than 31 days from now and try again.",
        "You cannot have a Send with an expiration date in the past. Adjust the expiration date and try again.",
        "You cannot have a Send with an expiration date greater than the deletion date. " +
            "Adjust the expiration date and try again.",
        "You cannot save a Send having an invalid AuthType",
        "File uploads are disabled",
        "Send storage limit reached! Delete some sends to free up space",
        "Send storage limit exceeded with this file",
        "Send content is not a file",
    )

private val terminalSendFileDirectUploadMessages =
    listOf(
        "Invalid content.",
        "Send does not have file data",
        "Not a File Type Send.",
        "File has already been uploaded.",
        "File received does not match expected file length.",
        "Send is not a file type send.",
        "Send file size does not match.",
    )

class SyncV2SendUploadIntegrationTest {
    @Test
    fun `new send file upload uses v2 create uploads bytes and clears pending state`() = runTest {
        withTempUploadFile("send create bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file = testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = runSendUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val local = store.locals.getValue("send-local-1")
            assertEquals("send-created-1", local.service.remote?.id)
            assertEquals("file-created-1", local.file?.id)
            assertNull(local.file?.pendingUpload)
            assertTrue(server.uploadedSendFileBodies.getValue("file-created-1").contains("send create bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/sends/file/v2",
                    HttpMethod.Post to "/api/sends/send-created-1/file/file-created-1",
                    HttpMethod.Get to "/api/sends/send-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps uploads new file send and clears staged file`() = runTest {
        withTempUploadFile("production send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = BitwardenCrImpl(
                cipherEncryptor = CipherEncryptorImpl(
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                ),
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            ).apply {
                appendProfileToken2(
                    keyData = ByteArray(64) { index -> (index + 11).toByte() },
                    privateKey = ByteArray(1),
                )
            }
            val coordinator = UploadTestPendingUploadCoordinator()
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file =
                        testSendFile(
                            id = "file-local-1",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() }),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenSend>>(
                ops.pushToServer(
                    local = local,
                    server = null,
                    force = false,
                ),
            )
            ops.saveLocal(
                local = outcome.local,
                previousLocal = local,
            )

            val saved = database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertEquals("send-created-1", saved.service.remote?.id)
            assertEquals("file-created-1", saved.file?.id)
            assertNull(saved.file?.pendingUpload)
            val uploadedBody = server.uploadedSendFileBodies["file-created-1"]
            assertNotNull(
                uploadedBody,
                "Uploaded send file was not captured. " +
                    "uploaded=${server.uploadedSendFileBodies.keys}; " +
                    "requests=${server.requests.map { it.method to it.path }}",
            )
            assertTrue(uploadedBody.contains("production send bytes"))
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps upload failure after create deletes remote placeholder and keeps pending file`() = runTest {
        withTempUploadFile("production failed send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.nextSendFileUploadFailure = HttpStatusCode.InternalServerError
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file =
                        testSendFile(
                            id = "file-local-1",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() }),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                SyncCoordinator().safeSyncEntityType(
                    EntitySyncConfig(
                        name = "sends",
                        strategy = SendSyncStrategy(),
                        localEntities = listOf(local),
                        serverEntities = emptyList(),
                        ops = ops,
                    ),
                ),
            )

            assertEquals(1, outcome.result.failures.size)
            val saved = database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertNull(saved.service.remote)
            assertEquals("file-local-1", saved.file?.id)
            assertEquals(pendingUpload, saved.file?.pendingUpload)
            assertEquals(500, saved.service.error?.code)
            assertNull(server.sends["send-created-1"])
            assertEquals(listOf("send-created-1"), server.deletedSendIds)
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/sends/file/v2",
                    HttpMethod.Post to "/api/sends/send-created-1/file/file-created-1",
                    HttpMethod.Delete to "/api/sends/send-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps clears stale error when merging successful remote metadata`() = runTest {
        withTempUploadFile("merged send bytes") { _, pendingUpload ->
            val fixture = createProductionSendOpsFixture(UploadTestServer())
            val staleError =
                BitwardenService.Error(
                    code = 503,
                    message = "previous send upload failed",
                    revisionDate = T0,
                )
            val current =
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
                ).let { send ->
                    send.copy(service = send.service.copy(error = staleError))
                }
            val remote =
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T4,
                    remoteRevisionDate = T4,
                    file = testSendFile(
                        id = "file-remote-1",
                        pendingUpload = pendingUpload,
                    ).copy(pendingUpload = null),
                )

            val merged =
                fixture.ops.mergeRemoteSuccessIntoChangedLocal(
                    current = current,
                    remoteLocal = remote,
                )

            assertEquals("send-remote-1", merged.service.remote?.id)
            assertEquals(T4, merged.service.remote?.revisionDate)
            assertNull(merged.service.error)
            assertEquals("file-remote-1", merged.file?.id)
            assertEquals(pendingUpload, merged.file?.pendingUpload)
        }
    }

    @Test
    fun `production SendSyncOps clears pending file on terminal reservation failures`() = runTest {
        terminalSendFileReservationMessages.forEach { message ->
            assertProductionSendPendingFileClearedOnFailure(message) { server ->
                server.nextSendFileReservationFailure = HttpStatusCode.BadRequest to message
            }
        }
    }

    @Test
    fun `production SendSyncOps clears pending file on terminal direct upload failures`() = runTest {
        terminalSendFileDirectUploadMessages.forEach { message ->
            assertProductionSendPendingFileClearedOnFailure(message) { server ->
                server.nextSendFileUploadFailure = HttpStatusCode.BadRequest
                server.nextSendFileUploadFailureMessage = message
            }
        }
    }

    @Test
    fun `production SendSyncOps clears pending file on forbidden and not found terminal direct upload failures`() = runTest {
        listOf(
            HttpStatusCode.Forbidden to "You must have premium status to use file Sends.",
            HttpStatusCode.NotFound to "Send does not have file data.",
        ).forEach { (status, message) ->
            assertProductionSendPendingFileClearedOnFailure(
                message = message,
                expectedStatusCode = status,
            ) { server ->
                server.nextSendFileUploadFailure = status
                server.nextSendFileUploadFailureMessage = message
            }
        }
    }

    @Test
    fun `production SendSyncOps preserves pending file on generic forbidden and not found direct upload failures`() = runTest {
        listOf(
            HttpStatusCode.Forbidden to "Forbidden",
            HttpStatusCode.NotFound to "Not Found",
        ).forEach { (status, message) ->
            assertProductionSendPendingFilePreservedOnFailure(
                message = message,
                expectedStatusCode = status,
            )
        }
    }

    @Test
    fun `production SendSyncOps preserves created remote state when terminal upload cleanup fails`() = runTest {
        withTempUploadFile("terminal send upload bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionSendOpsFixture(server)
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file =
                        testSendFile(
                            id = "file-local-1",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = fixture.sendKeyBase64(),
                )
            fixture.database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            server.nextSendFileUploadFailure = HttpStatusCode.BadRequest
            server.nextSendFileUploadFailureMessage =
                "File received does not match expected file length."
            server.sendDeleteFailuresById["send-created-1"] = HttpStatusCode.InternalServerError

            val failure = assertSendFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = null,
                    force = false,
                )
            }
            val failedLocal =
                fixture.ops.markRemoteFailure(
                    local = local,
                    remoteLocal = failure.partialRemoteLocal,
                    error = failure.cause,
                )
            fixture.ops.saveLocal(
                local = failedLocal,
                previousLocal = local,
            )

            val saved = fixture.database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertEquals("send-created-1", failure.partialRemoteLocal?.service?.remote?.id)
            assertEquals("send-created-1", saved.service.remote?.id)
            assertEquals("file-created-1", failure.partialRemoteLocal?.file?.id)
            assertEquals("file-created-1", saved.file?.id)
            assertNull(failure.partialRemoteLocal?.file?.pendingUpload)
            assertNull(saved.file?.pendingUpload)
            assertEquals(HttpStatusCode.BadRequest.value, saved.service.error?.code)
            assertTrue(
                saved.service.error?.message?.contains("File received does not match expected file length") == true,
            )
            assertEquals(listOf(pendingUpload), fixture.coordinator.deleteCalls)
            assertEquals(emptyList(), fixture.coordinator.markUploadedCalls)
            assertEquals(listOf("send-created-1"), server.deletedSendIds)
            assertTrue("send-created-1" in server.sends)
        }
    }

    @Test
    fun `production SendSyncOps remote refresh preserves pending send upload`() = runTest {
        withTempUploadFile("refreshed send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    file =
                        testSendFile(
                            id = "file-remote-1",
                            fileName = "send.bin",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() }),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )

            val remoteModel =
                local.copy(
                    revisionDate = T1,
                    service =
                        local.service.copy(
                            remote =
                                requireNotNull(local.service.remote).copy(
                                    revisionDate = T1,
                                ),
                        ),
                    name = "Remote Send",
                    notes = "Remote edit",
                    file = requireNotNull(local.file).copy(pendingUpload = null),
                )
            val remote = remoteModel.toEncryptedSendEntity(
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            val outcome = SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "sends",
                    strategy = SendSyncStrategy(),
                    localEntities = listOf(local),
                    serverEntities = listOf(remote),
                    ops = ops,
                ),
            )

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val saved = database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertEquals(T1, saved.revisionDate)
            assertEquals("Remote Send", saved.name)
            assertEquals("Remote edit", saved.notes)
            assertEquals(pendingUpload, saved.file?.pendingUpload)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps remote refresh propagates upload marker cancellation`() = runTest {
        withTempUploadFile("cancelled marker send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator(
                isUploadedFailure = CancellationException("cancelled upload marker check"),
            )
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    file =
                        testSendFile(
                            id = "file-remote-1",
                            fileName = "send.bin",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() }),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )

            val remoteModel =
                local.copy(
                    revisionDate = T1,
                    service =
                        local.service.copy(
                            remote =
                                requireNotNull(local.service.remote).copy(
                                    revisionDate = T1,
                                ),
                        ),
                    file = requireNotNull(local.file).copy(pendingUpload = null),
                )
            val remote = remoteModel.toEncryptedSendEntity(
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            assertFailsWith<CancellationException> {
                SyncCoordinator().safeSyncEntityType(
                    EntitySyncConfig(
                        name = "sends",
                        strategy = SendSyncStrategy(),
                        localEntities = listOf(local),
                        serverEntities = listOf(remote),
                        ops = ops,
                    ),
                )
            }
            val saved = database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertEquals(T0, saved.revisionDate)
            assertEquals(pendingUpload, saved.file?.pendingUpload)
        }
    }

    @Test
    fun `production SendSyncOps local delete removes pending staged file`() = runTest {
        withTempUploadFile("deleted send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file =
                        testSendFile(
                            id = "file-remote-1",
                            pendingUpload = pendingUpload,
                        ),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            val outcome = SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "sends",
                    strategy = SendSyncStrategy(),
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = ops,
                ),
            )

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertNull(database.sendQueries.getBySendId("send-local-1").executeAsOneOrNull())
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps delete forever deletes remote send and local row`() = runTest {
        withTempUploadFile("deleted send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val remote =
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        size = pendingUpload.encryptedSize,
                    ),
                )
            server.sends[remote.id] = remote
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = remote.id,
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file =
                        testSendFile(
                            id = "file-remote-1",
                            pendingUpload = pendingUpload,
                        ),
                ).let { send ->
                    send.copy(
                        service = send.service.copy(
                            deleted = true,
                        ),
                    )
                }
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            val ops = SendSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                sendsApi = server.env.api.sends,
                pendingUploadCoordinator = coordinator,
            )

            val outcome = SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "sends",
                    strategy = SendSyncStrategy(),
                    localEntities = listOf(local),
                    serverEntities = listOf(remote),
                    ops = ops,
                ),
            )

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertNull(database.sendQueries.getBySendId("send-local-1").executeAsOneOrNull())
            assertNull(server.sends["send-remote-1"])
            assertEquals(listOf("send-remote-1"), server.deletedSendIds)
            assertEquals(
                listOf(HttpMethod.Delete to "/api/sends/send-remote-1"),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production SendSyncOps cleanup is scoped to saved send`() = runTest {
        withTempUploadFile("production send bytes a") { _, pendingUploadA ->
            withTempUploadFile("production send bytes b") { _, pendingUploadB ->
                val server = UploadTestServer()
                val database = createUploadTestDatabase()
                val cryptoGenerator = CryptoGeneratorJvm()
                val base64Service = Base64ServiceJvm()
                val crypto = BitwardenCrImpl(
                    cipherEncryptor = CipherEncryptorImpl(
                        cryptoGenerator = cryptoGenerator,
                        base64Service = base64Service,
                    ),
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                ).apply {
                    appendProfileToken2(
                        keyData = ByteArray(64) { index -> (index + 11).toByte() },
                        privateKey = ByteArray(1),
                    )
                }
                val coordinator = UploadTestPendingUploadCoordinator()
                val sendKeyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() })
                val localA =
                    testSend(
                        localId = "send-local-a",
                        remoteId = null,
                        localRevisionDate = T2,
                        remoteRevisionDate = null,
                        file =
                            testSendFile(
                                id = "file-local-a",
                                pendingUpload = pendingUploadA,
                            ),
                    ).copy(keyBase64 = sendKeyBase64)
                val localB =
                    testSend(
                        localId = "send-local-b",
                        remoteId = null,
                        localRevisionDate = T2,
                        remoteRevisionDate = null,
                        file =
                            testSendFile(
                                id = "file-local-b",
                                pendingUpload = pendingUploadB,
                            ),
                    ).copy(keyBase64 = sendKeyBase64)
                listOf(localA, localB).forEach { local ->
                    database.sendQueries.insert(
                        accountId = local.accountId,
                        sendId = local.sendId,
                        data = local,
                    )
                }
                val ops = SendSyncOps(
                    accountId = ACCOUNT_ID,
                    db = database,
                    crypto = crypto,
                    cryptoGenerator = cryptoGenerator,
                    base64Service = base64Service,
                    httpClient = server.client,
                    env = server.env,
                    token = server.token,
                    sendsApi = server.env.api.sends,
                    pendingUploadCoordinator = coordinator,
                )

                val outcomeA = assertIs<RemoteWriteOutcome.Upsert<BitwardenSend>>(
                    ops.pushToServer(
                        local = localA,
                        server = null,
                        force = false,
                    ),
                )
                val outcomeB = assertIs<RemoteWriteOutcome.Upsert<BitwardenSend>>(
                    ops.pushToServer(
                        local = localB,
                        server = null,
                        force = false,
                    ),
                )

                ops.saveLocal(
                    local = outcomeB.local,
                    previousLocal = localB,
                )

                assertEquals(listOf(pendingUploadB), coordinator.deleteCalls)

                ops.saveLocal(
                    local = outcomeA.local,
                    previousLocal = localA,
                )

                assertEquals(listOf(pendingUploadB, pendingUploadA), coordinator.deleteCalls)
                assertEquals(listOf(pendingUploadA, pendingUploadB), coordinator.markUploadedCalls)
            }
        }
    }

    @Test
    fun `pending send upload is pushed when local and remote revisions match`() = runTest {
        withTempUploadFile("matched revision send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file =
                        testSendFileEntity(
                            id = "file-remote-1",
                            fileName = "send.bin",
                            size = pendingUpload.encryptedSize,
                        ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    file =
                        testSendFile(
                            id = "file-remote-1",
                            fileName = "send.bin",
                            pendingUpload = pendingUpload,
                        ),
                ),
            )

            val outcome = runSendUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertNull(store.locals.getValue("send-local-1").file?.pendingUpload)
            assertTrue(server.uploadedSendFileBodies.getValue("file-remote-1").contains("matched revision send bytes"))
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `SyncByBitwardenTokenV2Impl processes pending send upload when revision is unchanged`() = runTest {
        withTempUploadFile("full sync send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.revisionDate = "rev-stable"
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val cipherEncryptor = CipherEncryptorImpl(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val (user, profile) = createUploadTestUserAndProfile(
                server = server,
                cipherEncryptor = cipherEncryptor,
                base64Service = base64Service,
            )
            server.profile = profile
            val coordinator = UploadTestPendingUploadCoordinator()
            val local =
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file =
                        testSendFile(
                            id = "file-local-1",
                            pendingUpload = pendingUpload,
                        ),
                ).copy(
                    keyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() }),
                )
            database.sendQueries.insert(
                accountId = local.accountId,
                sendId = local.sendId,
                data = local,
            )
            database.metaQueries.insert(
                accountId = ACCOUNT_ID,
                data =
                    BitwardenMeta(
                        accountId = ACCOUNT_ID,
                        lastSyncResult = BitwardenMeta.LastSyncResult.Success,
                        lastServerRevisionDate = "rev-stable",
                        lastSyncServiceVersion = BitwardenService.VERSION,
                    ),
            )
            val sync = SyncByBitwardenTokenV2Impl(
                logRepository = UploadTestLogRepository,
                cipherEncryptor = cipherEncryptor,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                getPasswordStrength = UploadTestPasswordStrength,
                json = UploadTestServer.json,
                httpClient = server.client,
                db = UploadTestVaultDatabaseManager(database),
                dbSyncer = DatabaseSyncer(cryptoGenerator),
                pendingUploadCoordinator = coordinator,
                watchdog = UploadTestWatchdog,
                markBackupAsDirty = UploadTestMarkBackupAsDirty,
            )

            sync.invoke(user).invoke()

            val saved = database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
            assertEquals("send-created-1", saved.service.remote?.id)
            assertEquals("file-created-1", saved.file?.id)
            assertNull(saved.file?.pendingUpload)
            assertTrue(server.uploadedSendFileBodies.getValue("file-created-1").contains("full sync send bytes"))
            assertEquals("rev-stable", database.metaQueries.getByAccountId(ACCOUNT_ID).executeAsOne().data_.lastServerRevisionDate)
            assertEquals(
                listOf(
                    HttpMethod.Get to "/api/accounts/revision-date",
                    HttpMethod.Get to "/api/sync",
                    HttpMethod.Post to "/api/sends/file/v2",
                    HttpMethod.Post to "/api/sends/send-created-1/file/file-created-1",
                    HttpMethod.Get to "/api/sends/send-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `existing send file upload fetches upload target uploads bytes and clears pending state`() = runTest {
        withTempUploadFile("send update bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        size = pendingUpload.encryptedSize,
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = runSendUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val local = store.locals.getValue("send-local-1")
            assertEquals("file-remote-1", local.file?.id)
            assertNull(local.file?.pendingUpload)
            assertTrue(server.uploadedSendFileBodies.getValue("file-remote-1").contains("send update bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Put to "/api/sends/send-remote-1",
                    HttpMethod.Get to "/api/sends/send-remote-1/file/file-remote-1",
                    HttpMethod.Post to "/api/sends/send-remote-1/file/file-remote-1",
                    HttpMethod.Get to "/api/sends/send-remote-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `send uploaded marker reconciles matching remote file without reuploading bytes`() = runTest {
        withTempUploadFile("already uploaded send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        size = pendingUpload.encryptedSize,
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator(uploaded = setOf(pendingUpload))
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = runSendUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertNull(store.locals.getValue("send-local-1").file?.pendingUpload)
            assertEquals(emptyMap(), server.uploadedSendFileBodies)
            assertEquals(
                listOf(
                    HttpMethod.Put to "/api/sends/send-remote-1",
                    HttpMethod.Get to "/api/sends/send-remote-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `send uploaded marker with mismatched remote file reuploads bytes`() = runTest {
        withTempUploadFile("reuploaded send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        fileName = "other.bin",
                        size = 999L,
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator(uploaded = setOf(pendingUpload))
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = runSendUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertTrue(server.uploadedSendFileBodies.getValue("file-remote-1").contains("reuploaded send bytes"))
            assertEquals(listOf(pendingUpload), coordinator.markUploadedCalls)
        }
    }

    @Test
    fun `send upload failure after create deletes remote placeholder and preserves pending file`() = runTest {
        withTempUploadFile("failed send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.nextSendFileUploadFailure = HttpStatusCode.InternalServerError
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file = testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runSendUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("send-local-1")
            assertNull(local.service.remote)
            assertEquals("file-local-1", local.file?.id)
            assertEquals(pendingUpload, local.file?.pendingUpload)
            assertEquals(500, local.service.error?.code)
            assertNull(server.sends["send-created-1"])
            assertEquals(listOf("send-created-1"), server.deletedSendIds)
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/sends/file/v2",
                    HttpMethod.Post to "/api/sends/send-created-1/file/file-created-1",
                    HttpMethod.Delete to "/api/sends/send-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `send upload failure after create keeps remote metadata when cleanup delete fails`() = runTest {
        withTempUploadFile("failed send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.nextSendFileUploadFailure = HttpStatusCode.InternalServerError
            server.sendDeleteFailuresById["send-created-1"] = HttpStatusCode.InternalServerError
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = null,
                    localRevisionDate = T2,
                    remoteRevisionDate = null,
                    file = testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runSendUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("send-local-1")
            assertEquals("send-created-1", local.service.remote?.id)
            assertEquals("file-created-1", local.file?.id)
            assertEquals(pendingUpload, local.file?.pendingUpload)
            assertEquals(500, local.service.error?.code)
            assertEquals("send-created-1", server.sends["send-created-1"]?.id)
            assertEquals(listOf("send-created-1"), server.deletedSendIds)
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/sends/file/v2",
                    HttpMethod.Post to "/api/sends/send-created-1/file/file-created-1",
                    HttpMethod.Delete to "/api/sends/send-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `send upload failure after update keeps pending file referenced`() = runTest {
        withTempUploadFile("failed send update bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.nextSendFileUploadFailure = HttpStatusCode.InternalServerError
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        size = pendingUpload.encryptedSize,
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runSendUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("send-local-1")
            assertEquals("send-remote-1", local.service.remote?.id)
            assertEquals(pendingUpload, local.file?.pendingUpload)
            assertEquals(500, local.service.error?.code)
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `send uploaded marker with missing refreshed send preserves pending file`() = runTest {
        withTempUploadFile("missing refreshed send bytes") { _, pendingUpload ->
            val server = UploadTestServer()
            server.seedSend(
                testSendEntity(
                    id = "send-remote-1",
                    file = testSendFileEntity(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        size = pendingUpload.encryptedSize,
                    ),
                ),
            )
            server.nextSendGetFailure = HttpStatusCode.NotFound
            val coordinator = UploadTestPendingUploadCoordinator(uploaded = setOf(pendingUpload))
            val store = SendUploadStore(
                testSend(
                    localId = "send-local-1",
                    remoteId = "send-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    file = testSendFile(
                        id = "file-remote-1",
                        fileName = "send.bin",
                        pendingUpload = pendingUpload,
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runSendUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("send-local-1")
            assertEquals("send-remote-1", local.service.remote?.id)
            assertEquals(pendingUpload, local.file?.pendingUpload)
            assertEquals(404, local.service.error?.code)
            assertEquals(emptyMap(), server.uploadedSendFileBodies)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }
}

private suspend fun assertProductionSendPendingFileClearedOnFailure(
    message: String,
    expectedStatusCode: HttpStatusCode = HttpStatusCode.BadRequest,
    configureServer: (UploadTestServer) -> Unit,
) {
    withTempUploadFile("terminal send upload bytes") { _, pendingUpload ->
        val server = UploadTestServer()
        val fixture = createProductionSendOpsFixture(server)
        val local =
            testSend(
                localId = "send-local-1",
                remoteId = null,
                localRevisionDate = T2,
                remoteRevisionDate = null,
                file =
                    testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
            ).copy(
                keyBase64 = fixture.sendKeyBase64(),
            )
        fixture.database.sendQueries.insert(
            accountId = local.accountId,
            sendId = local.sendId,
            data = local,
        )
        configureServer(server)

        val failure = assertSendFailure {
            fixture.ops.pushToServer(
                local = local,
                server = null,
                force = false,
            )
        }
        val failedLocal =
            fixture.ops.markRemoteFailure(
                local = local,
                remoteLocal = failure.partialRemoteLocal,
                error = failure.cause,
            )
        fixture.ops.saveLocal(
            local = failedLocal,
            previousLocal = local,
        )

        val saved = fixture.database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
        assertNull(failure.partialRemoteLocal?.file?.pendingUpload, message)
        assertNull(saved.file?.pendingUpload, message)
        assertEquals(expectedStatusCode.value, saved.service.error?.code, message)
        assertTrue(saved.service.error?.message?.contains(message) == true, message)
        assertEquals(listOf(pendingUpload), fixture.coordinator.deleteCalls, message)
        assertEquals(emptyList(), fixture.coordinator.markUploadedCalls, message)
        assertEquals(emptyMap(), server.uploadedSendFileBodies, message)
    }
}

private suspend fun assertProductionSendPendingFilePreservedOnFailure(
    message: String,
    expectedStatusCode: HttpStatusCode,
) {
    withTempUploadFile("generic send upload bytes") { _, pendingUpload ->
        val server = UploadTestServer()
        val fixture = createProductionSendOpsFixture(server)
        val local =
            testSend(
                localId = "send-local-1",
                remoteId = null,
                localRevisionDate = T2,
                remoteRevisionDate = null,
                file =
                    testSendFile(
                        id = "file-local-1",
                        pendingUpload = pendingUpload,
                    ),
            ).copy(
                keyBase64 = fixture.sendKeyBase64(),
            )
        fixture.database.sendQueries.insert(
            accountId = local.accountId,
            sendId = local.sendId,
            data = local,
        )
        server.nextSendFileUploadFailure = expectedStatusCode
        server.nextSendFileUploadFailureMessage = message

        val failure = assertSendFailure {
            fixture.ops.pushToServer(
                local = local,
                server = null,
                force = false,
            )
        }
        val failedLocal =
            fixture.ops.markRemoteFailure(
                local = local,
                remoteLocal = failure.partialRemoteLocal,
                error = failure.cause,
            )
        fixture.ops.saveLocal(
            local = failedLocal,
            previousLocal = local,
        )

        val saved = fixture.database.sendQueries.getBySendId("send-local-1").executeAsOne().data_
        assertNull(saved.service.remote, message)
        assertEquals("file-local-1", saved.file?.id, message)
        assertEquals(pendingUpload, saved.file?.pendingUpload, message)
        assertEquals(expectedStatusCode.value, saved.service.error?.code, message)
        assertNull(server.sends["send-created-1"], message)
        assertEquals(listOf("send-created-1"), server.deletedSendIds, message)
        assertEquals(emptyList(), fixture.coordinator.deleteCalls, message)
        assertEquals(emptyList(), fixture.coordinator.markUploadedCalls, message)
    }
}

private data class ProductionSendOpsFixture(
    val database: Database,
    val base64Service: Base64ServiceJvm,
    val coordinator: UploadTestPendingUploadCoordinator,
    val ops: SendSyncOps,
) {
    fun sendKeyBase64(): String =
        base64Service.encodeToString(ByteArray(64) { index -> (index + 65).toByte() })
}

private fun createProductionSendOpsFixture(
    server: UploadTestServer,
    coordinator: UploadTestPendingUploadCoordinator = UploadTestPendingUploadCoordinator(),
): ProductionSendOpsFixture {
    val database = createUploadTestDatabase()
    val cryptoGenerator = CryptoGeneratorJvm()
    val base64Service = Base64ServiceJvm()
    val crypto = createUploadTestCrypto(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    )
    val ops = SendSyncOps(
        accountId = ACCOUNT_ID,
        db = database,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        httpClient = server.client,
        env = server.env,
        token = server.token,
        sendsApi = server.env.api.sends,
        pendingUploadCoordinator = coordinator,
    )
    return ProductionSendOpsFixture(
        database = database,
        base64Service = base64Service,
        coordinator = coordinator,
        ops = ops,
    )
}

private suspend fun runSendUploadSync(
    server: UploadTestServer,
    store: SendUploadStore,
    pendingUploadCoordinator: UploadTestPendingUploadCoordinator,
) = SyncCoordinator().safeSyncEntityType(
    EntitySyncConfig(
        name = "sends",
        strategy = SendSyncStrategy(),
        localEntities = store.locals.values.toList(),
        serverEntities = server.sends.values.toList(),
        ops = SendUploadIntegrationOps(server, store, pendingUploadCoordinator),
    ),
)

private class SendUploadStore(
    initial: BitwardenSend,
) {
    val locals = linkedMapOf(initial.sendId to initial)
}

private class SendUploadIntegrationOps(
    private val server: UploadTestServer,
    private val store: SendUploadStore,
    private val pendingUploadCoordinator: UploadTestPendingUploadCoordinator,
) : EntitySyncOps<BitwardenSend, SendEntity> {
    override suspend fun readLocal(localId: String): BitwardenSend? = store.locals[localId]

    override suspend fun insertOrUpdateLocally(entries: List<Pair<SendEntity, BitwardenSend?>>) = Unit

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<SendEntity, BitwardenSend>>,
    ): LocalUpdateResult {
        val applied = entries.count { entry -> entry.shouldUpdate(store.locals[entry.localId]) }
        return LocalUpdateResult(
            applied = applied,
            skipped = entries.size - applied,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        localIds.forEach(store.locals::remove)
    }

    override suspend fun saveLocal(
        local: BitwardenSend,
        previousLocal: BitwardenSend?,
    ) {
        store.locals[local.sendId] = local
        val pendingUpload = local.file?.pendingUpload
        val obsoleteUploads = previousLocal
            ?.file
            ?.pendingUpload
            ?.let(::listOf)
            .orEmpty()
            .filter { previousPendingUpload ->
                pendingUpload?.path != previousPendingUpload.path
            }
        obsoleteUploads.forEach { upload ->
            pendingUploadCoordinator.delete(upload)
        }
    }

    override suspend fun pushToServer(
        local: BitwardenSend,
        server: SendEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenSend> {
        val pendingUpload = requireNotNull(local.file?.pendingUpload)
        val api = this.server.env.api.sends
        var partialRemoteLocal: BitwardenSend? = null
        val remoteLocal =
            if (server == null) {
                val response = api.postFileV2(
                    httpClient = this.server.client,
                    env = this.server.env,
                    token = this.server.token,
                    body = local.toSendRequest(),
                )
                val remoteSend = response.requiredSendResponse
                partialRemoteLocal = remoteSend.toLocalSend(local.sendId)
                if (!pendingUploadCoordinator.isUploaded(pendingUpload)) {
                    try {
                        uploadSendFile(
                            httpClient = this.server.client,
                            env = this.server.env,
                            token = this.server.token,
                            target = response.uploadTarget,
                            fileName = requireNotNull(remoteSend.file?.fileName),
                            filePath = pendingUpload.path,
                            fileLength = pendingUpload.encryptedSize,
                        )
                    } catch (e: Throwable) {
                        val cleanupResult =
                            withContext(NonCancellable) {
                                runCatching {
                                    api.focus(remoteSend.id).delete(
                                        httpClient = this@SendUploadIntegrationOps.server.client,
                                        env = this@SendUploadIntegrationOps.server.env,
                                        token = this@SendUploadIntegrationOps.server.token,
                                    ).status
                                }
                            }
                        val cleanupSucceeded =
                            cleanupResult.fold(
                                onSuccess = { status ->
                                    status == HttpStatusCode.NotFound ||
                                        status.value in 200..299
                                },
                                onFailure = { cleanupError ->
                                    cleanupError.hasHttpStatusCode(HttpStatusCode.NotFound)
                                },
                            )
                        val failurePartial =
                            partialRemoteLocal
                                .takeUnless { cleanupSucceeded }
                        coroutineContext.ensureActive()
                        if (e is CancellationException) throw e
                        return RemoteWriteOutcome.Failure(failurePartial, e)
                    }
                    pendingUploadCoordinator.markUploaded(pendingUpload)
                }
                api.focus(remoteSend.id).get(
                    httpClient = this.server.client,
                    env = this.server.env,
                    token = this.server.token,
                ).toLocalSend(local.sendId)
            } else {
                val sendApi = api.focus(server.id)
                val putResponse = sendApi.put(
                    httpClient = this.server.client,
                    env = this.server.env,
                    token = this.server.token,
                    body = local.toSendRequest(),
                )
                partialRemoteLocal = putResponse.toLocalSend(local.sendId)
                val completedRemoteUpload =
                    if (pendingUploadCoordinator.isUploaded(pendingUpload)) {
                        val refreshedLocal = sendApi.get(
                            httpClient = this.server.client,
                            env = this.server.env,
                            token = this.server.token,
                        ).toLocalSend(local.sendId)
                        val reconciliation = refreshedLocal
                            .reconcilePendingSendFileUpload(
                                local = local,
                                uploadCompletedLocally = true,
                            )
                        if (reconciliation.obsoletePendingUpload != null) {
                            reconciliation.send
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                completedRemoteUpload ?: run {
                    val uploadTarget = sendApi.getFileUploadTarget(
                        httpClient = this.server.client,
                        env = this.server.env,
                        token = this.server.token,
                        fileId = requireNotNull(local.file).id,
                    )
                    try {
                        uploadSendFile(
                            httpClient = this.server.client,
                            env = this.server.env,
                            token = this.server.token,
                            target = uploadTarget.uploadTarget,
                            fileName = putResponse.file?.fileName
                                ?: server.file?.fileName
                                ?: error("Bitwarden send response must contain a file name for upload."),
                            filePath = pendingUpload.path,
                            fileLength = pendingUpload.encryptedSize,
                        )
                    } catch (e: Throwable) {
                        return RemoteWriteOutcome.Failure(partialRemoteLocal, e)
                    }
                    pendingUploadCoordinator.markUploaded(pendingUpload)
                    sendApi.get(
                        httpClient = this.server.client,
                        env = this.server.env,
                        token = this.server.token,
                    ).toLocalSend(local.sendId)
                }
            }

        val reconciliation = remoteLocal.reconcilePendingSendFileUpload(
            local = local,
            uploadCompletedLocally = true,
        )
        return RemoteWriteOutcome.Upsert(reconciliation.send)
    }

    override suspend fun deleteOnServer(
        local: BitwardenSend,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenSend> = error("unused")

    override suspend fun mergeConflict(
        local: BitwardenSend,
        server: SendEntity,
    ): RemoteWriteOutcome<BitwardenSend> = pushToServer(local, server, force = true)

    override suspend fun markRemoteFailure(
        local: BitwardenSend,
        remoteLocal: BitwardenSend?,
        error: Throwable,
    ): BitwardenSend {
        val currentPendingUpload = local.file?.pendingUpload
        val remoteFile = remoteLocal?.file
        val localWithRemote =
            if (currentPendingUpload != null && remoteFile != null) {
                local.copy(
                    service = local.service.copy(
                        remote = remoteLocal.service.remote,
                        version = remoteLocal.service.version,
                    ),
                    type = BitwardenSend.Type.File,
                    file = remoteFile.copy(
                        pendingUpload = currentPendingUpload,
                    ),
                    text = null,
                )
            } else {
                local
            }
        return super.markRemoteFailure(
            local = localWithRemote,
            remoteLocal = null,
            error = error,
        )
    }
}

private fun BitwardenSend.toEncryptedSendEntity(
    crypto: BitwardenCrImpl,
    cryptoGenerator: CryptoGeneratorJvm,
    base64Service: Base64ServiceJvm,
): SendEntity {
    val itemKey = requireNotNull(keyBase64).let(base64Service::decode)
    val (itemCrypto, globalCrypto) = buildSendCodecPair(
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        mode = BitwardenCrCta.Mode.ENCRYPT,
        key = itemKey,
    )
    val encrypted = transform(
        itemCrypto = itemCrypto,
        globalCrypto = globalCrypto,
    )
    val encryptedFile = requireNotNull(encrypted.file)
    return SendEntity(
        id = requireNotNull(encrypted.service.remote?.id),
        accessId = encrypted.accessId,
        key = encrypted.keyBase64,
        type = SendTypeEntity.File,
        name = encrypted.name,
        notes = encrypted.notes,
        file =
            SendFileEntity(
                id = encryptedFile.id,
                fileName = encryptedFile.fileName,
                size = encryptedFile.size?.toString(),
                sizeName = encryptedFile.sizeName,
            ),
        accessCount = encrypted.accessCount,
        maxAccessCount = encrypted.maxAccessCount,
        revisionDate = encrypted.revisionDate,
        creationDate = encrypted.createdDate,
        expirationDate = encrypted.expirationDate,
        deletionDate = encrypted.deletedDate,
        password = encrypted.password,
        disabled = encrypted.disabled,
        hideEmail = encrypted.hideEmail,
        authType = null,
    )
}

private fun BitwardenSend.toSendRequest(): SendRequest = with(UploadTestCryptoGenerator) {
    with(UploadTestBase64Service) {
        SendRequest.of(
            model = this@toSendRequest,
            key = "account-key".encodeToByteArray(),
        )
    }
}
