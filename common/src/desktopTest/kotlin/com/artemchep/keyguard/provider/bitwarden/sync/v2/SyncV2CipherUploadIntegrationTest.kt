package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.copy.Base64ServiceJvm
import com.artemchep.keyguard.core.store.DatabaseSyncer
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.mergePendingAttachmentRemoteIdsFrom
import com.artemchep.keyguard.core.store.bitwarden.pendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.pendingRemoteAttachmentDeletionIds
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.withPendingAttachmentRemoteId
import com.artemchep.keyguard.crypto.CipherEncryptorImpl
import com.artemchep.keyguard.crypto.CryptoGeneratorJvm
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.provider.bitwarden.api.builder.api
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.postV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.renew
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadCipherAttachment
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrImpl
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.appendOrganizationToken2
import com.artemchep.keyguard.provider.bitwarden.entity.AttachmentEntity
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherAttachmentCreateRequest
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.ops.CipherSyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.CipherSyncStrategy
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Instant

private suspend fun assertCipherFailure(
    block: suspend () -> RemoteWriteOutcome<BitwardenCipher>,
): RemoteWriteOutcome.Failure<BitwardenCipher> =
    assertIs<RemoteWriteOutcome.Failure<BitwardenCipher>>(block())

private val terminalCipherAttachmentReservationMessages =
    listOf(
        "Max file size is 500 MB.",
        "You do not have permissions to edit this.",
        "No data to attach.",
        "Not enough storage available.",
        "You must have premium status to use attachments.",
        "Not enough storage available for this organization.",
        "Attachments are disabled",
        "Attachment storage limit reached! Delete some attachments to free up space",
        "Attachment storage limit exceeded with this file",
        "Cipher is not write accessible",
        "Cipher doesn't exist",
    )

private val terminalCipherAttachmentDirectUploadMessages =
    listOf(
        "Invalid content.",
        "Cipher attachment does not exist",
        "File received does not match expected file length.",
        "Attachment size mismatch (expected within [0, 1], got 2)",
    )

class SyncV2CipherUploadIntegrationTest {
    @Test
    fun `production CipherSyncOps clears stale error when merging successful remote metadata`() = runTest {
        val fixture = createProductionCipherOpsFixture(UploadTestServer())
        val staleError =
            BitwardenService.Error(
                code = 503,
                message = "previous cipher upload failed",
                revisionDate = T0,
            )
        val current =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).let { cipher ->
                cipher.copy(
                    name = "Local Cipher",
                    service = cipher.service.copy(error = staleError),
                )
            }
        val remote =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T4,
                remoteRevisionDate = T4,
                attachments = emptyList(),
            ).copy(name = "Remote Cipher")

        val merged =
            fixture.ops.mergeRemoteSuccessIntoChangedLocal(
                current = current,
                remoteLocal = remote,
            )

        assertEquals("Local Cipher", merged.name)
        assertEquals("cipher-remote-1", merged.service.remote?.id)
        assertEquals(T4, merged.service.remote?.revisionDate)
        assertNull(merged.service.error)
        assertEquals(remote, merged.remoteEntity)
    }

    @Test
    fun `cipher pending attachment upload reserves target uploads bytes and clears pending state`() = runTest {
        withTempUploadFile("cipher upload bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 19L,
                            keyBase64 = "attachment-key",
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ),
            )

            val outcome = runCipherUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val local = store.locals.getValue("cipher-local-1")
            val attachment = assertIs<BitwardenCipher.Attachment.Remote>(local.attachments.single())
            assertEquals("attachment-created-1", attachment.id)
            assertEquals("cipher.bin", attachment.fileName)
            assertEquals(19L, attachment.size)
            assertNull(local.service.error)
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("cipher upload bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/ciphers/cipher-remote-1/attachment/v2",
                    HttpMethod.Post to "/api/ciphers/cipher-remote-1/attachment/attachment-created-1/data",
                    HttpMethod.Get to "/api/ciphers/cipher-remote-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(pendingUpload.copy(remoteId = "attachment-created-1")), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `cipher uploaded marker reconciles existing remote attachment without reuploading bytes`() = runTest {
        withTempUploadFile("already uploaded cipher bytes") { file, pendingUpload ->
            val uploadedPending = pendingUpload.copy(remoteId = "attachment-reserved-1")
            val server = UploadTestServer()
            server.seedCipher(
                testCipherEntity(
                    id = "cipher-remote-1",
                    attachments = listOf(
                        testAttachmentEntity(
                            id = "attachment-reserved-1",
                            fileName = "cipher.bin",
                            key = "attachment-key",
                            size = uploadedPending.encryptedSize,
                        ),
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator(uploaded = setOf(uploadedPending))
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 19L,
                            keyBase64 = "attachment-key",
                            pendingUpload = uploadedPending,
                        ),
                    ),
                ),
            )

            val outcome = runCipherUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val attachment = assertIs<BitwardenCipher.Attachment.Remote>(
                store.locals.getValue("cipher-local-1").attachments.single(),
            )
            assertEquals("attachment-reserved-1", attachment.id)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertEquals(
                listOf(HttpMethod.Get to "/api/ciphers/cipher-remote-1"),
                server.requests.map { it.method to it.path },
            )
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(listOf(uploadedPending), coordinator.deleteCalls)
        }
    }

    @Test
    fun `cipher reserved attachment retry renews target before reuploading`() = runTest {
        withTempUploadFile("renewed cipher bytes") { file, pendingUpload ->
            val reservedPending = pendingUpload.copy(remoteId = "attachment-reserved-1")
            val server = UploadTestServer()
            server.seedCipher(
                testCipherEntity(
                    id = "cipher-remote-1",
                    attachments = listOf(
                        testAttachmentEntity(
                            id = "attachment-reserved-1",
                            fileName = "cipher.bin",
                            key = "attachment-key",
                            size = reservedPending.encryptedSize,
                        ),
                    ),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 19L,
                            keyBase64 = "attachment-key",
                            pendingUpload = reservedPending,
                        ),
                    ),
                ),
            )

            val outcome = runCipherUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-reserved-1").contains("renewed cipher bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Get to "/api/ciphers/cipher-remote-1/attachment/attachment-reserved-1/renew",
                    HttpMethod.Post to "/api/ciphers/cipher-remote-1/attachment/attachment-reserved-1/data",
                    HttpMethod.Get to "/api/ciphers/cipher-remote-1",
                ),
                server.requests.map { it.method to it.path },
            )
            assertEquals(listOf(reservedPending), coordinator.markUploadedCalls)
        }
    }

    @Test
    fun `cipher renew 404 creates replacement reservation before reuploading`() = runTest {
        withTempUploadFile("replacement cipher bytes") { file, pendingUpload ->
            val reservedPending = pendingUpload.copy(remoteId = "attachment-missing-1")
            val server = UploadTestServer()
            server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
            server.renewNotFoundAttachmentIds += "attachment-missing-1"
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 24L,
                            keyBase64 = "attachment-key",
                            pendingUpload = reservedPending,
                        ),
                    ),
                ),
            )

            val outcome = runCipherUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            val attachment = assertIs<BitwardenCipher.Attachment.Remote>(
                store.locals.getValue("cipher-local-1").attachments.single(),
            )
            assertEquals("attachment-created-1", attachment.id)
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("replacement cipher bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Get to "/api/ciphers/cipher-remote-1/attachment/attachment-missing-1/renew",
                    HttpMethod.Post to "/api/ciphers/cipher-remote-1/attachment/v2",
                    HttpMethod.Post to "/api/ciphers/cipher-remote-1/attachment/attachment-created-1/data",
                    HttpMethod.Get to "/api/ciphers/cipher-remote-1",
                ),
                server.requests.map { it.method to it.path },
            )
        }
    }

    @Test
    fun `cipher upload failure after reservation preserves pending metadata and deletes placeholder`() = runTest {
        withTempUploadFile("failed cipher bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
            server.nextCipherAttachmentUploadFailure = HttpStatusCode.InternalServerError
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 19L,
                            keyBase64 = "attachment-key",
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runCipherUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("cipher-local-1")
            val attachment = assertIs<BitwardenCipher.Attachment.Local>(local.attachments.single())
            assertEquals("attachment-created-1", attachment.pendingUpload?.remoteId)
            assertEquals(500, local.service.error?.code)
            assertEquals(emptyList(), coordinator.markUploadedCalls)
            assertEquals(emptyList(), coordinator.deleteCalls)
            assertEquals(listOf("attachment-created-1"), server.deletedCipherAttachmentIds)
        }
    }

    @Test
    fun `cipher reservation response without attachment id records failure without uploading`() = runTest {
        withTempUploadFile("reservation missing id bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
            server.nextCipherAttachmentReservationMissingId = true
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "cipher.bin",
                            size = 28L,
                            keyBase64 = "attachment-key",
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ),
            )

            val outcome = assertIs<EntityTypeOutcome.Completed>(
                runCipherUploadSync(server, store, coordinator),
            )

            assertEquals(1, outcome.result.failures.size)
            val local = store.locals.getValue("cipher-local-1")
            val attachment = assertIs<BitwardenCipher.Attachment.Local>(local.attachments.single())
            assertNull(attachment.pendingUpload?.remoteId)
            assertEquals(BitwardenService.Error.CODE_UNKNOWN, local.service.error?.code)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertEquals(emptyList(), coordinator.deleteCalls)
        }
    }

    @Test
    fun `cipher multiple pending attachments upload while preserving existing remote attachment`() = runTest {
        withTempUploadFile("first cipher bytes") { firstFile, firstPending ->
            withTempUploadFile("second cipher bytes") { secondFile, secondPending ->
                val existingRemoteAttachment =
                    testAttachmentEntity(
                        id = "attachment-existing-1",
                        fileName = "existing.bin",
                        key = "existing-key",
                        size = 7L,
                    )
                val server = UploadTestServer()
                server.seedCipher(
                    testCipherEntity(
                        id = "cipher-remote-1",
                        attachments = listOf(existingRemoteAttachment),
                    ),
                )
                val coordinator = UploadTestPendingUploadCoordinator()
                val store = CipherUploadStore(
                    testCipher(
                        localId = "cipher-local-1",
                        remoteId = "cipher-remote-1",
                        localRevisionDate = T2,
                        remoteRevisionDate = T0,
                        attachments = listOf(
                            existingRemoteAttachment.toLocalRemoteAttachment(),
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-1",
                                url = "file://${firstFile.absolutePath}",
                                fileName = "first.bin",
                                size = 18L,
                                keyBase64 = "first-key",
                                pendingUpload = firstPending,
                            ),
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-2",
                                url = "file://${secondFile.absolutePath}",
                                fileName = "second.bin",
                                size = 19L,
                                keyBase64 = "second-key",
                                pendingUpload = secondPending,
                            ),
                        ),
                    ),
                )

                val outcome = runCipherUploadSync(server, store, coordinator)

                assertIs<EntityTypeOutcome.Completed>(outcome)
                assertEquals(
                    listOf("attachment-existing-1", "attachment-created-1", "attachment-created-2"),
                    store.locals.getValue("cipher-local-1").attachments.map { it.id },
                )
                assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("first cipher bytes"))
                assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-2").contains("second cipher bytes"))
                assertEquals(
                    listOf(
                        firstPending,
                        secondPending,
                    ),
                    coordinator.deleteCalls,
                )
            }
        }
    }

    @Test
    fun `cipher multiple attachments keep staged files when later upload fails`() = runTest {
        withTempUploadFile("first cipher bytes") { firstFile, firstPending ->
            withTempUploadFile("second cipher bytes") { secondFile, secondPending ->
                val server = UploadTestServer()
                server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
                server.cipherAttachmentUploadFailuresById["attachment-created-2"] = HttpStatusCode.InternalServerError
                val coordinator = UploadTestPendingUploadCoordinator()
                val store = CipherUploadStore(
                    testCipher(
                        localId = "cipher-local-1",
                        remoteId = "cipher-remote-1",
                        localRevisionDate = T2,
                        remoteRevisionDate = T0,
                        attachments = listOf(
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-1",
                                url = "file://${firstFile.absolutePath}",
                                fileName = "first.bin",
                                size = 18L,
                                keyBase64 = "first-key",
                                pendingUpload = firstPending,
                            ),
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-2",
                                url = "file://${secondFile.absolutePath}",
                                fileName = "second.bin",
                                size = 19L,
                                keyBase64 = "second-key",
                                pendingUpload = secondPending,
                            ),
                        ),
                    ),
                )

                val outcome = assertIs<EntityTypeOutcome.Completed>(
                    runCipherUploadSync(server, store, coordinator),
                )

                assertEquals(1, outcome.result.failures.size)
                val localAttachments =
                    store.locals.getValue("cipher-local-1")
                        .attachments
                        .filterIsInstance<BitwardenCipher.Attachment.Local>()
                assertEquals(
                    listOf("attachment-created-1", "attachment-created-2"),
                    localAttachments.map { it.pendingUpload?.remoteId },
                )
                assertEquals(listOf(firstPending.copy(remoteId = "attachment-created-1")), coordinator.markUploadedCalls)
                assertEquals(emptyList(), coordinator.deleteCalls)
                assertEquals(listOf("attachment-created-2"), server.deletedCipherAttachmentIds)
            }
        }
    }

    @Test
    fun `cipher remote attachment deletion and pending upload are synced together`() = runTest {
        withTempUploadFile("mixed cipher bytes") { file, pendingUpload ->
            val keptRemote =
                testAttachmentEntity(
                    id = "attachment-keep-1",
                    fileName = "keep.bin",
                    key = "keep-key",
                    size = 7L,
                )
            val deletedRemote =
                testAttachmentEntity(
                    id = "attachment-delete-1",
                    fileName = "delete.bin",
                    key = "delete-key",
                    size = 8L,
                )
            val remoteEntity =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        keptRemote.toLocalRemoteAttachment(),
                        deletedRemote.toLocalRemoteAttachment(),
                    ),
                )
            val server = UploadTestServer()
            server.seedCipher(
                testCipherEntity(
                    id = "cipher-remote-1",
                    attachments = listOf(keptRemote, deletedRemote),
                ),
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val store = CipherUploadStore(
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    remoteEntity = remoteEntity,
                    attachments = listOf(
                        keptRemote.toLocalRemoteAttachment(),
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "mixed.bin",
                            size = 18L,
                            keyBase64 = "mixed-key",
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ),
            )

            val outcome = runCipherUploadSync(server, store, coordinator)

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertEquals(listOf("attachment-delete-1"), server.deletedCipherAttachmentIds)
            assertEquals(
                listOf("attachment-keep-1", "attachment-created-1"),
                store.locals.getValue("cipher-local-1").attachments.map { it.id },
            )
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("mixed cipher bytes"))
        }
    }

    @Test
    fun `production CipherSyncOps uploads pending attachment and clears staged file`() = runTest {
        withTempUploadFile("production cipher bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            val database = createUploadTestDatabase()
            val cryptoGenerator = CryptoGeneratorJvm()
            val base64Service = Base64ServiceJvm()
            val crypto = createUploadTestCrypto(
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
            )
            val coordinator = UploadTestPendingUploadCoordinator()
            val cipherKeyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 21).toByte() })
            val attachmentKeyBase64 = base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "production-cipher.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = attachmentKeyBase64,
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(
                    keyBase64 = cipherKeyBase64,
                )
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = crypto,
                        base64Service = base64Service,
                    ),
            )
            database.cipherQueries.insert(
                cipherId = local.cipherId,
                accountId = local.accountId,
                folderId = local.folderId,
                data = local,
                updatedAt = local.revisionDate,
            )
            val ops = CipherSyncOps(
                accountId = ACCOUNT_ID,
                db = database,
                crypto = crypto,
                cryptoGenerator = cryptoGenerator,
                base64Service = base64Service,
                getPasswordStrength = UploadTestPasswordStrength,
                logRepository = UploadTestLogRepository,
                httpClient = server.client,
                env = server.env,
                token = server.token,
                ciphersApi = server.env.api.ciphers,
                encryptedFor = "profile-1",
                remoteToLocalFolders = emptyMap(),
                localToRemoteFolders = emptyMap(),
                serverFolders = emptyList(),
                pendingUploadCoordinator = coordinator,
            )

            val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
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

            val saved = database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
            assertEquals("cipher-remote-1", saved.service.remote?.id)
            val remoteAttachment = assertIs<BitwardenCipher.Attachment.Remote>(saved.attachments.single())
            assertEquals("attachment-created-1", remoteAttachment.id)
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("production cipher bytes"))
            assertEquals(listOf(pendingUpload.copy(remoteId = "attachment-created-1")), coordinator.markUploadedCalls)
            assertEquals(listOf(pendingUpload), coordinator.deleteCalls)
        }
    }

    @Test
    fun `production CipherSyncOps decodes attachment refresh with response item key`() = runTest {
        withTempUploadFile("response-key attachment bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val attachmentKeyBase64 =
                fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "unused-remote",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "response-key.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = attachmentKeyBase64,
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(
                    keyBase64 = fixture.cipherKeyBase64(),
                    name = "Client Created Cipher",
                    service = BitwardenService(version = BitwardenService.VERSION),
                )
            val responseKeyBase64 =
                fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 61).toByte() })
            server.nextCipherGetResponse =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-created-1",
                    localRevisionDate = T4,
                    remoteRevisionDate = T4,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Remote(
                            id = "attachment-created-1",
                            url = "https://vault.example.com/attachments/attachment-created-1",
                            fileName = "response-key.bin",
                            keyBase64 = attachmentKeyBase64,
                            size = pendingUpload.encryptedSize,
                        ),
                    ),
                ).copy(
                    keyBase64 = responseKeyBase64,
                    name = "Server Attachment Refresh Cipher",
                ).toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )

            val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
                fixture.ops.pushToServer(
                    local = local,
                    server = null,
                    force = false,
                ),
            )

            assertEquals("Server Attachment Refresh Cipher", outcome.local.name)
            assertEquals(responseKeyBase64, outcome.local.keyBase64)
            val attachment = assertIs<BitwardenCipher.Attachment.Remote>(outcome.local.attachments.single())
            assertEquals("attachment-created-1", attachment.id)
            assertTrue(server.uploadedCipherAttachmentBodies.getValue("attachment-created-1").contains("response-key attachment bytes"))
            assertEquals(
                listOf(
                    HttpMethod.Post to "/api/ciphers/",
                    HttpMethod.Post to "/api/ciphers/cipher-created-1/attachment/v2",
                    HttpMethod.Post to "/api/ciphers/cipher-created-1/attachment/attachment-created-1/data",
                    HttpMethod.Get to "/api/ciphers/cipher-created-1",
                ),
                server.requests.map { it.method to it.path },
            )
        }
    }

    @Test
    fun `production CipherSyncOps removes pending attachments when organization rejects attachments`() = runTest {
        withTempUploadFile("unsupported organization cipher bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            fixture.crypto.appendOrganizationToken2(
                id = "organization-1",
                keyData = ByteArray(64) { index -> (index + 101).toByte() },
            )
            val attachmentKeyBase64 =
                fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "unsupported-org.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = attachmentKeyBase64,
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(
                    keyBase64 = fixture.cipherKeyBase64(),
                    organizationId = "organization-1",
                )
            val remote =
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    )
            server.seedCipher(remote)
            fixture.database.cipherQueries.insert(
                cipherId = local.cipherId,
                accountId = local.accountId,
                folderId = local.folderId,
                data = local,
                updatedAt = local.revisionDate,
            )
            server.nextCipherAttachmentReservationFailure =
                HttpStatusCode.BadRequest to "This organization cannot use attachments."

            val failure = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = remote,
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

            val saved = fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
            assertEquals(emptyList(), failure.partialRemoteLocal?.pendingLocalAttachments().orEmpty())
            assertEquals(emptyList(), saved.pendingLocalAttachments())
            assertEquals(emptyList(), saved.attachments)
            assertEquals(HttpStatusCode.BadRequest.value, saved.service.error?.code)
            assertTrue(saved.service.error?.message?.contains("This organization cannot use attachments") == true)
            assertEquals(listOf(pendingUpload), fixture.coordinator.deleteCalls)
            assertEquals(emptyList(), fixture.coordinator.markUploadedCalls)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertEquals(emptyList(), server.deletedCipherAttachmentIds)
        }
    }

    @Test
    fun `production CipherSyncOps removes pending attachments on terminal reservation failures`() = runTest {
        terminalCipherAttachmentReservationMessages.forEach { message ->
            assertProductionCipherPendingAttachmentClearedOnFailure(message) { server ->
                server.nextCipherAttachmentReservationFailure = HttpStatusCode.BadRequest to message
            }
        }
    }

    @Test
    fun `production CipherSyncOps removes pending attachments on terminal renew failures`() = runTest {
        terminalCipherAttachmentReservationMessages.forEach { message ->
            assertProductionCipherPendingAttachmentClearedOnFailure(
                message = message,
                pendingUploadRemoteId = "attachment-reserved-1",
            ) { server ->
                server.renewFailuresByAttachmentId["attachment-reserved-1"] = HttpStatusCode.BadRequest
                server.renewFailureMessagesByAttachmentId["attachment-reserved-1"] = message
            }
        }
    }

    @Test
    fun `production CipherSyncOps removes pending attachments on terminal direct upload failures`() = runTest {
        terminalCipherAttachmentDirectUploadMessages.forEach { message ->
            assertProductionCipherPendingAttachmentClearedOnFailure(message) { server ->
                server.nextCipherAttachmentUploadFailure = HttpStatusCode.BadRequest
                server.nextCipherAttachmentUploadFailureMessage = message
            }
        }
    }

    @Test
    fun `production CipherSyncOps removes pending attachments on forbidden and not found terminal direct upload failures`() = runTest {
        listOf(
            HttpStatusCode.Forbidden to "You do not have permissions to edit this.",
            HttpStatusCode.NotFound to "Cipher attachment does not exist.",
        ).forEach { (status, message) ->
            assertProductionCipherPendingAttachmentClearedOnFailure(
                message = message,
                expectedStatusCode = status,
            ) { server ->
                server.nextCipherAttachmentUploadFailure = status
                server.nextCipherAttachmentUploadFailureMessage = message
            }
        }
    }

    @Test
    fun `production CipherSyncOps preserves pending attachment on generic forbidden and not found direct upload failures`() = runTest {
        listOf(
            HttpStatusCode.Forbidden to "Forbidden",
            HttpStatusCode.NotFound to "Not Found",
        ).forEach { (status, message) ->
            assertProductionCipherPendingAttachmentPreservedOnFailure(
                message = message,
                expectedStatusCode = status,
            )
        }
    }

    @Test
    fun `production CipherSyncOps preserves pending attachment when terminal upload cleanup fails`() = runTest {
        withTempUploadFile("terminal cipher bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "terminal-cipher.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 =
                                fixture.base64Service.encodeToString(
                                    ByteArray(64) { index -> (index + 31).toByte() },
                                ),
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(
                    keyBase64 = fixture.cipherKeyBase64(),
                )
            val remote =
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    )
            server.seedCipher(remote)
            fixture.database.cipherQueries.insert(
                cipherId = local.cipherId,
                accountId = local.accountId,
                folderId = local.folderId,
                data = local,
                updatedAt = local.revisionDate,
            )
            server.nextCipherAttachmentUploadFailure = HttpStatusCode.BadRequest
            server.nextCipherAttachmentUploadFailureMessage =
                "File received does not match expected file length."
            server.cipherAttachmentDeleteFailuresById["attachment-created-1"] =
                HttpStatusCode.InternalServerError

            val failure = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = remote,
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

            val expectedPendingUpload = pendingUpload.copy(remoteId = "attachment-created-1")
            val failedPartialPending = failure.partialRemoteLocal?.pendingLocalAttachments().orEmpty()
            val saved = fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
            val savedPending = saved.pendingLocalAttachments()
            assertEquals(listOf("attachment-local-1"), failedPartialPending.map { it.id })
            assertEquals(listOf("attachment-local-1"), savedPending.map { it.id })
            assertEquals(expectedPendingUpload, failedPartialPending.single().pendingUpload)
            assertEquals(expectedPendingUpload, savedPending.single().pendingUpload)
            assertEquals(HttpStatusCode.BadRequest.value, saved.service.error?.code)
            assertTrue(
                saved.service.error?.message?.contains("File received does not match expected file length") == true,
            )
            assertEquals(emptyList(), fixture.coordinator.deleteCalls)
            assertEquals(emptyList(), fixture.coordinator.markUploadedCalls)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertEquals(listOf("attachment-created-1"), server.deletedCipherAttachmentIds)
            assertTrue(
                server.ciphers
                    .getValue("cipher-remote-1")
                    .attachments
                    .orEmpty()
                    .any { attachment -> attachment.id == "attachment-created-1" },
            )
        }
    }

    @Test
    fun `production CipherSyncOps preserves other pending attachments on terminal upload failure`() = runTest {
        withTempUploadFile("terminal cipher bytes") { firstFile, firstPendingUpload ->
            withTempUploadFile("remaining cipher bytes") { secondFile, secondPendingUpload ->
                val server = UploadTestServer()
                val fixture = createProductionCipherOpsFixture(server)
                val firstAttachmentKeyBase64 =
                    fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
                val secondAttachmentKeyBase64 =
                    fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 61).toByte() })
                val local =
                    testCipher(
                        localId = "cipher-local-1",
                        remoteId = "cipher-remote-1",
                        localRevisionDate = T0,
                        remoteRevisionDate = T0,
                        attachments = listOf(
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-1",
                                url = "file://${firstFile.absolutePath}",
                                fileName = "terminal-cipher.bin",
                                size = firstPendingUpload.encryptedSize,
                                keyBase64 = firstAttachmentKeyBase64,
                                pendingUpload = firstPendingUpload,
                            ),
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-2",
                                url = "file://${secondFile.absolutePath}",
                                fileName = "remaining-cipher.bin",
                                size = secondPendingUpload.encryptedSize,
                                keyBase64 = secondAttachmentKeyBase64,
                                pendingUpload = secondPendingUpload,
                            ),
                        ),
                    ).copy(
                        keyBase64 = fixture.cipherKeyBase64(),
                    )
                val remote =
                    local.copy(attachments = emptyList())
                        .toEncryptedCipherEntity(
                            crypto = fixture.crypto,
                            base64Service = fixture.base64Service,
                        )
                server.seedCipher(remote)
                fixture.database.cipherQueries.insert(
                    cipherId = local.cipherId,
                    accountId = local.accountId,
                    folderId = local.folderId,
                    data = local,
                    updatedAt = local.revisionDate,
                )
                server.nextCipherAttachmentUploadFailure = HttpStatusCode.BadRequest
                server.nextCipherAttachmentUploadFailureMessage =
                    "File received does not match expected file length."

                val failure = assertCipherFailure {
                    fixture.ops.pushToServer(
                        local = local,
                        server = remote,
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

                val failedPartialPending = failure.partialRemoteLocal?.pendingLocalAttachments().orEmpty()
                val saved = fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
                val savedPending = saved.pendingLocalAttachments()
                assertEquals(listOf("attachment-local-2"), failedPartialPending.map { it.id })
                assertEquals(listOf("attachment-local-2"), savedPending.map { it.id })
                assertEquals(secondPendingUpload, savedPending.single().pendingUpload)
                assertEquals(HttpStatusCode.BadRequest.value, saved.service.error?.code)
                assertTrue(
                    saved.service.error?.message?.contains("File received does not match expected file length") == true,
                )
                assertEquals(listOf(firstPendingUpload), fixture.coordinator.deleteCalls)
                assertEquals(emptyList(), fixture.coordinator.markUploadedCalls)
                assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
                assertEquals(listOf("attachment-created-1"), server.deletedCipherAttachmentIds)
            }
        }
    }

    @Test
    fun `production CipherSyncOps local delete removes pending staged attachments`() = runTest {
        withTempUploadFile("deleted cipher bytes") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val attachmentKeyBase64 =
                fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T2,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "deleted-cipher.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = attachmentKeyBase64,
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            fixture.database.cipherQueries.insert(
                cipherId = local.cipherId,
                accountId = local.accountId,
                folderId = local.folderId,
                data = local,
                updatedAt = local.revisionDate,
            )

            val outcome = SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "ciphers",
                    strategy = CipherSyncStrategy(
                        remoteFolderIdToLocalId = { it },
                    ),
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = fixture.ops,
                ),
            )

            assertIs<EntityTypeOutcome.Completed>(outcome)
            assertNull(fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOneOrNull())
            assertEquals(listOf(pendingUpload), fixture.coordinator.deleteCalls)
        }
    }

    @Test
    fun `production CipherSyncOps cleanup is scoped to saved cipher`() = runTest {
        withTempUploadFile("production cipher bytes a") { fileA, pendingUploadA ->
            withTempUploadFile("production cipher bytes b") { fileB, pendingUploadB ->
                val server = UploadTestServer()
                val fixture = createProductionCipherOpsFixture(server)
                val attachmentKeyBase64A = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 31).toByte() })
                val attachmentKeyBase64B = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 41).toByte() })
                val localA =
                    testCipher(
                        localId = "cipher-local-a",
                        remoteId = "cipher-remote-a",
                        localRevisionDate = T0,
                        remoteRevisionDate = T0,
                        attachments = listOf(
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-a",
                                url = "file://${fileA.absolutePath}",
                                fileName = "production-cipher-a.bin",
                                size = pendingUploadA.encryptedSize,
                                keyBase64 = attachmentKeyBase64A,
                                pendingUpload = pendingUploadA,
                            ),
                        ),
                    ).copy(keyBase64 = fixture.cipherKeyBase64())
                val localB =
                    testCipher(
                        localId = "cipher-local-b",
                        remoteId = "cipher-remote-b",
                        localRevisionDate = T0,
                        remoteRevisionDate = T0,
                        attachments = listOf(
                            BitwardenCipher.Attachment.Local(
                                id = "attachment-local-b",
                                url = "file://${fileB.absolutePath}",
                                fileName = "production-cipher-b.bin",
                                size = pendingUploadB.encryptedSize,
                                keyBase64 = attachmentKeyBase64B,
                                pendingUpload = pendingUploadB,
                            ),
                        ),
                    ).copy(keyBase64 = fixture.cipherKeyBase64())
                listOf(localA, localB).forEach { local ->
                    server.seedCipher(
                        local.copy(attachments = emptyList())
                            .toEncryptedCipherEntity(
                                crypto = fixture.crypto,
                                base64Service = fixture.base64Service,
                            ),
                    )
                    fixture.database.cipherQueries.insert(
                        cipherId = local.cipherId,
                        accountId = local.accountId,
                        folderId = local.folderId,
                        data = local,
                        updatedAt = local.revisionDate,
                    )
                }

                val outcomeA = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
                    fixture.ops.pushToServer(
                        local = localA,
                        server = server.ciphers.getValue("cipher-remote-a"),
                        force = false,
                    ),
                )
                val outcomeB = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
                    fixture.ops.pushToServer(
                        local = localB,
                        server = server.ciphers.getValue("cipher-remote-b"),
                        force = false,
                    ),
                )

                fixture.ops.saveLocal(
                    local = outcomeB.local,
                    previousLocal = localB,
                )

                assertEquals(listOf(pendingUploadB), fixture.coordinator.deleteCalls)

                fixture.ops.saveLocal(
                    local = outcomeA.local,
                    previousLocal = localA,
                )

                assertEquals(listOf(pendingUploadB, pendingUploadA), fixture.coordinator.deleteCalls)
                assertEquals(
                    listOf(
                        pendingUploadA.copy(remoteId = "attachment-created-1"),
                        pendingUploadB.copy(remoteId = "attachment-created-2"),
                    ),
                    fixture.coordinator.markUploadedCalls,
                )
            }
        }
    }

    @Test
    fun `production CipherSyncOps creates user cipher with regular create endpoint`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "unused-remote",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                service = BitwardenService(version = BitwardenService.VERSION),
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = null,
                force = false,
            ),
        )

        assertEquals("cipher-created-1", outcome.local.service.remote?.id)
        assertNull(outcome.local.organizationId)
        assertEquals(listOf(HttpMethod.Post to "/api/ciphers/"), server.requests.map { it.method to it.path })
    }

    @Test
    fun `production CipherSyncOps creates organization cipher with organization create endpoint`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        fixture.crypto.appendOrganizationToken2(
            id = "organization-1",
            keyData = ByteArray(64) { index -> (index + 101).toByte() },
        )
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "unused-remote",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                organizationId = "organization-1",
                service = BitwardenService(version = BitwardenService.VERSION),
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = null,
                force = false,
            ),
        )

        assertEquals("cipher-created-1", outcome.local.service.remote?.id)
        assertEquals("organization-1", outcome.local.organizationId)
        assertEquals(listOf(HttpMethod.Post to "/api/ciphers/create"), server.requests.map { it.method to it.path })
    }

    @Test
    fun `production CipherSyncOps decodes create response with response item key`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "unused-remote",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                name = "Client Created Cipher",
                service = BitwardenService(version = BitwardenService.VERSION),
            )
        val responseKeyBase64 =
            fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 41).toByte() })
        server.nextCipherCreateResponse =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-created-1",
                localRevisionDate = T4,
                remoteRevisionDate = T4,
                attachments = emptyList(),
            ).copy(
                keyBase64 = responseKeyBase64,
                name = "Server Created Cipher",
            ).toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = null,
                force = false,
            ),
        )

        assertEquals("Server Created Cipher", outcome.local.name)
        assertEquals(responseKeyBase64, outcome.local.keyBase64)
        assertEquals("cipher-created-1", outcome.local.service.remote?.id)
        assertEquals(listOf(HttpMethod.Post to "/api/ciphers/"), server.requests.map { it.method to it.path })
    }

    @Test
    fun `production CipherSyncOps decodes put response with response item key`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                name = "Client Edited Cipher",
            )
        val responseKeyBase64 =
            fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 51).toByte() })
        server.seedCipher(
            local.copy(name = "Old Server Cipher")
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                ),
        )
        server.nextCipherPutResponse =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T4,
                remoteRevisionDate = T4,
                attachments = emptyList(),
            ).copy(
                keyBase64 = responseKeyBase64,
                name = "Server Put Cipher",
            ).toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            ),
        )

        assertEquals("Server Put Cipher", outcome.local.name)
        assertEquals(responseKeyBase64, outcome.local.keyBase64)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps decodes personal to organization transfer response with server organization key`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        fixture.crypto.appendOrganizationToken2(
            id = "organization-1",
            keyData = ByteArray(64) { index -> (index + 101).toByte() },
        )
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                organizationId = null,
                collectionIds = emptySet(),
            )
        server.seedCipher(
            local.copy(
                organizationId = "organization-1",
                collectionIds = setOf("collection-1"),
            ).toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            ),
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            ),
        )

        assertEquals("organization-1", outcome.local.organizationId)
        assertEquals(setOf("collection-1"), outcome.local.collectionIds)
        assertEquals(fixture.cipherKeyBase64(), outcome.local.keyBase64)
        assertEquals("Cipher", outcome.local.name)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps decodes organization transfer response with destination organization key`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        fixture.crypto.appendOrganizationToken2(
            id = "organization-old",
            keyData = ByteArray(64) { index -> (index + 101).toByte() },
        )
        fixture.crypto.appendOrganizationToken2(
            id = "organization-new",
            keyData = ByteArray(64) { index -> (index + 111).toByte() },
        )
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                organizationId = "organization-old",
                collectionIds = setOf("collection-old"),
            )
        server.seedCipher(
            local.copy(
                organizationId = "organization-new",
                collectionIds = setOf("collection-new"),
            ).toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            ),
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            ),
        )

        assertEquals("organization-new", outcome.local.organizationId)
        assertEquals(setOf("collection-new"), outcome.local.collectionIds)
        assertEquals(fixture.cipherKeyBase64(), outcome.local.keyBase64)
        assertEquals("Cipher", outcome.local.name)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps decode failure after push creates decode failure partial`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
            )
        server.seedCipher(
            local.toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            ),
        )
        server.corruptNextCipherGetResponse = true

        val error = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            )
        }

        val partial = assertIs<BitwardenCipher>(error.partialRemoteLocal)
        assertEquals("cipher-remote-1", partial.service.remote?.id)
        assertEquals(BitwardenService.Error.CODE_DECODING_FAILED, partial.service.error?.code)
        assertEquals(listOf(HttpMethod.Get to "/api/ciphers/cipher-remote-1"), server.requests.map { it.method to it.path })
    }

    @Test
    fun `production CipherSyncOps decode failure after partial push preserves existing partial`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                service = BitwardenService(
                    remote = BitwardenService.Remote(
                        id = "cipher-remote-1",
                        revisionDate = T0,
                        deletedDate = T0,
                    ),
                    version = BitwardenService.VERSION,
                ),
            )
        server.seedCipher(
            local.copy(deletedDate = T0)
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
                .copy(deletedDate = T0),
        )
        server.corruptNextCipherGetResponse = true

        val error = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            )
        }

        val partial = assertIs<BitwardenCipher>(error.partialRemoteLocal)
        assertEquals("cipher-remote-1", partial.service.remote?.id)
        assertEquals(T4, partial.service.remote?.revisionDate)
        assertNull(partial.service.remote?.deletedDate)
        assertNull(partial.service.error)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/restore",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps upload failure for existing attachment skips cleanup delete`() = runTest {
        withTempUploadFile("reserved upload failure") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val reservedPending = pendingUpload.copy(remoteId = "attachment-reserved-1")
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "reserved.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 51).toByte() }),
                            pendingUpload = reservedPending,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    ),
            )
            server.cipherAttachmentUploadFailuresById["attachment-reserved-1"] = HttpStatusCode.InternalServerError

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertIs<HttpException>(error.cause)
            assertEquals(emptyList(), server.deletedCipherAttachmentIds)
            assertEquals(listOf(HttpMethod.Get, HttpMethod.Post), server.requests.takeLast(2).map { it.method })
        }
    }

    @Test
    fun `production CipherSyncOps propagates non not found renew failure`() = runTest {
        withTempUploadFile("renew failure") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val reservedPending = pendingUpload.copy(remoteId = "attachment-reserved-1")
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "reserved.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 61).toByte() }),
                            pendingUpload = reservedPending,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    ),
            )
            server.renewFailuresByAttachmentId["attachment-reserved-1"] = HttpStatusCode.InternalServerError

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertIs<HttpException>(error.cause)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertEquals(emptyList(), server.deletedCipherAttachmentIds)
        }
    }

    @Test
    fun `production CipherSyncOps propagates attachment delete failure`() = runTest {
        withTempUploadFile("delete failure should happen before this upload") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val attachmentKeyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 41).toByte() })
            val keptAttachment =
                BitwardenCipher.Attachment.Remote(
                    id = "attachment-keep-1",
                    url = "https://vault.example.com/attachments/attachment-keep-1",
                    fileName = "keep.bin",
                    keyBase64 = attachmentKeyBase64,
                    size = 7L,
                )
            val deletedAttachment =
                BitwardenCipher.Attachment.Remote(
                    id = "attachment-delete-1",
                    url = "https://vault.example.com/attachments/attachment-delete-1",
                    fileName = "delete.bin",
                    keyBase64 = attachmentKeyBase64,
                    size = 8L,
                )
            val localAttachment =
                BitwardenCipher.Attachment.Local(
                    id = "attachment-local-1",
                    url = "file://${file.absolutePath}",
                    fileName = "new.bin",
                    size = pendingUpload.encryptedSize,
                    keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 42).toByte() }),
                    pendingUpload = pendingUpload,
                )
            val remoteEntity =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(keptAttachment, deletedAttachment),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            val local =
                remoteEntity.copy(
                    revisionDate = T2,
                    attachments = listOf(keptAttachment, localAttachment),
                    remoteEntity = remoteEntity,
                )
            server.seedCipher(
                remoteEntity.toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                ),
            )
            server.cipherAttachmentDeleteFailuresById["attachment-delete-1"] = HttpStatusCode.InternalServerError

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertEquals(listOf("attachment-delete-1"), server.deletedCipherAttachmentIds)
            assertTrue(
                server.requests.any { request ->
                    request.method == HttpMethod.Delete &&
                            request.path == "/api/ciphers/cipher-remote-1/attachment/attachment-delete-1"
                },
            )
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertIs<HttpException>(error.cause)
        }
    }

    @Test
    fun `production CipherSyncOps propagates get failure after attachment delete`() = runTest {
        withTempUploadFile("delete refresh failure should stop this upload") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val attachmentKeyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 43).toByte() })
            val keptAttachment =
                BitwardenCipher.Attachment.Remote(
                    id = "attachment-keep-1",
                    url = "https://vault.example.com/attachments/attachment-keep-1",
                    fileName = "keep.bin",
                    keyBase64 = attachmentKeyBase64,
                    size = 7L,
                )
            val deletedAttachment =
                BitwardenCipher.Attachment.Remote(
                    id = "attachment-delete-1",
                    url = "https://vault.example.com/attachments/attachment-delete-1",
                    fileName = "delete.bin",
                    keyBase64 = attachmentKeyBase64,
                    size = 8L,
                )
            val localAttachment =
                BitwardenCipher.Attachment.Local(
                    id = "attachment-local-1",
                    url = "file://${file.absolutePath}",
                    fileName = "new.bin",
                    size = pendingUpload.encryptedSize,
                    keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 44).toByte() }),
                    pendingUpload = pendingUpload,
                )
            val remoteEntity =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(keptAttachment, deletedAttachment),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            val local =
                remoteEntity.copy(
                    revisionDate = T2,
                    attachments = listOf(keptAttachment, localAttachment),
                    remoteEntity = remoteEntity,
                )
            server.seedCipher(
                remoteEntity.toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                ),
            )
            server.nextCipherGetFailure = HttpStatusCode.InternalServerError
            server.cipherGetFailureAfterSuccessfulGets = 1

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertEquals(listOf("attachment-delete-1"), server.deletedCipherAttachmentIds)
            assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies)
            assertIs<HttpException>(error.cause)
        }
    }

    @Test
    fun `production CipherSyncOps falls back to reservation cipher response when get after upload fails`() = runTest {
        withTempUploadFile("fallback get failure") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "fallback.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 71).toByte() }),
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    ),
            )
            server.nextCipherGetFailure = HttpStatusCode.InternalServerError
            server.cipherGetFailureAfterSuccessfulGets = 1

            val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                ),
            )

            assertEquals("attachment-created-1", outcome.local.attachments.single().id)
            assertEquals(listOf(pendingUpload.copy(remoteId = "attachment-created-1")), fixture.coordinator.markUploadedCalls)
        }
    }

    @Test
    fun `production CipherSyncOps propagates get failure when reservation has no cipher fallback`() = runTest {
        withTempUploadFile("missing fallback") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "fallback.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 81).toByte() }),
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    ),
            )
            server.nextCipherAttachmentReservationWithoutCipherResponse = true
            server.nextCipherGetFailure = HttpStatusCode.InternalServerError
            server.cipherGetFailureAfterSuccessfulGets = 1

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertIs<HttpException>(error.cause)
            assertEquals(listOf(pendingUpload.copy(remoteId = "attachment-created-1")), fixture.coordinator.markUploadedCalls)
        }
    }

    @Test
    fun `production CipherSyncOps fails when uploaded attachment is missing from remote`() = runTest {
        withTempUploadFile("missing remote attachment") { file, pendingUpload ->
            val server = UploadTestServer()
            val fixture = createProductionCipherOpsFixture(server)
            val local =
                testCipher(
                    localId = "cipher-local-1",
                    remoteId = "cipher-remote-1",
                    localRevisionDate = T0,
                    remoteRevisionDate = T0,
                    attachments = listOf(
                        BitwardenCipher.Attachment.Local(
                            id = "attachment-local-1",
                            url = "file://${file.absolutePath}",
                            fileName = "missing.bin",
                            size = pendingUpload.encryptedSize,
                            keyBase64 = fixture.base64Service.encodeToString(ByteArray(64) { index -> (index + 91).toByte() }),
                            pendingUpload = pendingUpload,
                        ),
                    ),
                ).copy(keyBase64 = fixture.cipherKeyBase64())
            server.seedCipher(
                local.copy(attachments = emptyList())
                    .toEncryptedCipherEntity(
                        crypto = fixture.crypto,
                        base64Service = fixture.base64Service,
                    ),
            )
            server.nextCipherAttachmentReservationWithoutRemoteAttachment = true

            val error = assertCipherFailure {
                fixture.ops.pushToServer(
                    local = local,
                    server = server.ciphers.getValue("cipher-remote-1"),
                    force = false,
                )
            }

            assertEquals("Failed to locate the uploaded cipher attachment on remote.", error.cause.message)
        }
    }

    @Test
    fun `production CipherSyncOps records decode failure during local insert`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val badServerCipher = testCipherEntity(id = "cipher-remote-1")

        fixture.ops.insertOrUpdateLocally(listOf(badServerCipher to null))

        val saved = fixture.database.cipherQueries
            .getByAccountId(ACCOUNT_ID)
            .executeAsList()
            .single()
            .data_
        assertEquals("cipher-remote-1", saved.service.remote?.id)
        assertEquals(BitwardenService.Error.CODE_DECODING_FAILED, saved.service.error?.code)
        assertTrue(requireNotNull(saved.name).contains("Unsupported Item"))
    }

    @Test
    fun `production CipherSyncOps merge conflict decode failure falls back to local copy`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(keyBase64 = fixture.cipherKeyBase64())
        val badServerCipher = testCipherEntity(id = "cipher-remote-1")

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.mergeConflict(local, badServerCipher),
        )

        assertEquals(local.cipherId, outcome.local.cipherId)
        assertEquals(local.name, outcome.local.name)
        assertEquals("cipher-remote-1", outcome.local.service.remote?.id)
        assertEquals(BitwardenService.Error.CODE_DECODING_FAILED, outcome.local.service.error?.code)
    }

    @Test
    fun `production CipherSyncOps merge conflict pushes three way password history merge`() = runTest {
        val server = UploadTestServer()
        server.cipherPutAppliesRequestBody = true
        val fixture = createProductionCipherOpsFixture(server)
        val sharedHistory = passwordHistory("shared-history", T0)
        val base =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).toLoginCipher(
                keyBase64 = fixture.cipherKeyBase64(),
                password = "base-password",
                passwordRevisionDate = T0,
                totp = "base-totp",
                passwordHistory = listOf(sharedHistory),
            )
        val local =
            base.copy(
                revisionDate = T2,
                remoteEntity = base,
                login = requireNotNull(base.login).copy(
                    password = "local-password",
                    passwordRevisionDate = T2,
                    totp = "local-totp",
                ),
                passwordHistory = listOf(
                    sharedHistory,
                    passwordHistory("local-history", T2),
                ),
            )
        val remote =
            base.copy(
                revisionDate = T3,
                service = requireNotNull(base.service.remote)
                    .copy(revisionDate = T3)
                    .let { base.service.copy(remote = it) },
                login = requireNotNull(base.login).copy(
                    password = "remote-password",
                    passwordRevisionDate = T3,
                    totp = "remote-totp",
                ),
                passwordHistory = listOf(
                    sharedHistory,
                    passwordHistory("remote-history", T3),
                ),
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.mergeConflict(
                local = local,
                server = remote.toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                ),
            ),
        )

        val mergedPasswords = outcome.local.passwordHistory.map { it.password }
        assertEquals("remote-password", outcome.local.login?.password)
        assertTrue("shared-history" in mergedPasswords)
        assertTrue("remote-history" in mergedPasswords)
        assertTrue("local-history" in mergedPasswords)
        assertTrue("local-password" in mergedPasswords)
        assertTrue("totp: local-totp" in mergedPasswords)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps merge conflict fallback pushes merged local password history`() = runTest {
        val server = UploadTestServer()
        server.cipherPutAppliesRequestBody = true
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).toLoginCipher(
                keyBase64 = fixture.cipherKeyBase64(),
                password = "local-password",
                passwordRevisionDate = T2,
                passwordHistory = listOf(passwordHistory("local-history", T2)),
            )
        val remote =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T3,
                remoteRevisionDate = T3,
                attachments = emptyList(),
            ).toLoginCipher(
                keyBase64 = fixture.cipherKeyBase64(),
                password = "remote-password",
                passwordRevisionDate = T3,
                passwordHistory = listOf(passwordHistory("remote-history", T3)),
            )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.mergeConflict(
                local = local,
                server = remote.toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                ),
            ),
        )

        val mergedPasswords = outcome.local.passwordHistory.map { it.password }
        assertEquals("remote-password", outcome.local.login?.password)
        assertTrue("remote-history" in mergedPasswords)
        assertTrue("local-history" in mergedPasswords)
        assertTrue("local-password" in mergedPasswords)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps propagates cancellation during decrypt`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val cancellingOps = CipherSyncOps(
            accountId = ACCOUNT_ID,
            db = fixture.database,
            crypto = CancellingDecodeBitwardenCr(fixture.crypto),
            cryptoGenerator = fixture.cryptoGenerator,
            base64Service = fixture.base64Service,
            getPasswordStrength = UploadTestPasswordStrength,
            logRepository = UploadTestLogRepository,
            httpClient = server.client,
            env = server.env,
            token = server.token,
            ciphersApi = server.env.api.ciphers,
            encryptedFor = "profile-1",
            remoteToLocalFolders = emptyMap(),
            localToRemoteFolders = emptyMap(),
            serverFolders = emptyList(),
            pendingUploadCoordinator = fixture.coordinator,
        )

        assertFailsWith<CancellationException> {
            cancellingOps.insertOrUpdateLocally(listOf(testCipherEntity(id = "cipher-remote-1") to null))
        }

        assertEquals(emptyList(), fixture.database.cipherQueries.getByAccountId(ACCOUNT_ID).executeAsList())
    }

    @Test
    fun `production CipherSyncOps restores legacy trashed cipher before uploading generated item key`() = runTest {
        val server = UploadTestServer()
        server.cipherPutAppliesRequestBody = true
        val fixture = createProductionCipherOpsFixture(server)
        val remoteBase =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = null,
                name = "Legacy Cipher",
                deletedDate = T0,
                service = BitwardenService(
                    remote = BitwardenService.Remote(
                        id = "cipher-remote-1",
                        revisionDate = T0,
                        deletedDate = T0,
                    ),
                    version = BitwardenService.VERSION,
                ),
            )
        val generatedItemKey = fixture.cipherKeyBase64()
        val local =
            remoteBase.copy(
                keyBase64 = generatedItemKey,
                name = "Edited Legacy Cipher",
                revisionDate = T2,
            )
        server.seedCipher(
            remoteBase.toEncryptedCipherEntity(
                crypto = fixture.crypto,
                base64Service = fixture.base64Service,
            ),
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            ),
        )

        assertEquals("Edited Legacy Cipher", outcome.local.name)
        assertEquals(generatedItemKey, outcome.local.keyBase64)
        assertEquals(T4, outcome.local.deletedDate)
        assertEquals(T4, outcome.local.service.remote?.deletedDate)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/restore",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/delete",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps keeps restored partial when subsequent put fails`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                service = BitwardenService(
                    remote = BitwardenService.Remote(
                        id = "cipher-remote-1",
                        revisionDate = T0,
                        deletedDate = T0,
                    ),
                    version = BitwardenService.VERSION,
                ),
            )
        server.seedCipher(
            local.copy(deletedDate = T0)
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
                .copy(deletedDate = T0),
        )
        server.nextCipherPutFailure = HttpStatusCode.InternalServerError

        val error = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            )
        }

        val partial = assertIs<BitwardenCipher>(error.partialRemoteLocal)
        assertEquals("cipher-remote-1", partial.service.remote?.id)
        assertEquals(T4, partial.service.remote?.revisionDate)
        assertNull(partial.service.remote?.deletedDate)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/restore",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps keeps put partial when trash fails`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                deletedDate = T2,
            )
        server.seedCipher(
            local.copy(deletedDate = null)
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
                .copy(deletedDate = null),
        )
        server.nextCipherTrashException = HttpException(
            statusCode = HttpStatusCode.InternalServerError,
            m = "trash failed",
            e = null,
        )

        val error = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            )
        }

        val partial = assertIs<BitwardenCipher>(error.partialRemoteLocal)
        assertEquals("cipher-remote-1", partial.service.remote?.id)
        assertEquals(T4, partial.service.remote?.revisionDate)
        assertNull(partial.service.remote?.deletedDate)
        assertIs<HttpException>(error.cause)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/delete",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps accepts empty trash response`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                deletedDate = T2,
            )
        server.seedCipher(
            local.copy(deletedDate = null)
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
                .copy(deletedDate = null),
        )

        val outcome = assertIs<RemoteWriteOutcome.Upsert<BitwardenCipher>>(
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            ),
        )

        assertEquals(T4, outcome.local.deletedDate)
        assertEquals(T4, outcome.local.service.remote?.deletedDate)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/delete",
                HttpMethod.Get to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `production CipherSyncOps propagates non-success empty trash response`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
                deletedDate = T2,
            )
        server.seedCipher(
            local.copy(deletedDate = null)
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
                .copy(deletedDate = null),
        )
        server.nextCipherTrashFailure = HttpStatusCode.Forbidden

        val error = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = server.ciphers.getValue("cipher-remote-1"),
                force = false,
            )
        }

        val partial = assertIs<BitwardenCipher>(error.partialRemoteLocal)
        assertEquals("cipher-remote-1", partial.service.remote?.id)
        assertEquals(T4, partial.service.remote?.revisionDate)
        assertNull(partial.service.remote?.deletedDate)
        val httpException = assertIs<HttpException>(error.cause)
        assertEquals(HttpStatusCode.Forbidden, httpException.statusCode)
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/cipher-remote-1",
                HttpMethod.Put to "/api/ciphers/cipher-remote-1/delete",
            ),
            server.requests.map { it.method to it.path },
        )
    }

    @Test
    fun `SyncByBitwardenTokenV2Impl uses cipher bulk hard delete for pending local removals`() = runTest {
        val server = UploadTestServer()
        server.revisionDate = "rev-stable"
        server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
        server.seedCipher(testCipherEntity(id = "cipher-remote-2"))
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

        val locals = listOf(
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ),
            testCipher(
                localId = "cipher-local-2",
                remoteId = "cipher-remote-2",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ),
        ).map { local ->
            local.copy(
                service = local.service.copy(deleted = true),
            )
        }
        locals.forEach { local ->
            database.cipherQueries.insert(
                cipherId = local.cipherId,
                accountId = local.accountId,
                folderId = local.folderId,
                data = local,
                updatedAt = local.revisionDate,
            )
        }
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
            pendingUploadCoordinator = UploadTestPendingUploadCoordinator(),
            watchdog = UploadTestWatchdog,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
        )

        sync.invoke(user).invoke()

        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
                HttpMethod.Delete to "/api/ciphers/",
            ),
            server.requests.map { it.method to it.path },
        )
        val bulkDelete =
            server.requests.single { request ->
                request.method == HttpMethod.Delete && request.path == "/api/ciphers/"
            }
        val bulkDeleteIds =
            UploadTestServer.json
                .parseToJsonElement(bulkDelete.body)
                .jsonObject
                .getValue("ids")
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
        assertEquals(setOf("cipher-remote-1", "cipher-remote-2"), bulkDeleteIds)
        assertTrue(
            server.requests.none { request ->
                request.method == HttpMethod.Delete &&
                    request.path.startsWith("/api/ciphers/") &&
                    request.path != "/api/ciphers/"
            },
        )
        assertTrue(
            server.requests.none { request ->
                request.method == HttpMethod.Put &&
                    request.path.startsWith("/api/ciphers/") &&
                    request.path.endsWith("/delete") &&
                    request.path != "/api/ciphers/delete"
            },
        )
        assertEquals(
            emptyList(),
            database.cipherQueries
                .getByAccountId(ACCOUNT_ID)
                .executeAsList(),
        )
        assertEquals(emptyMap(), server.ciphers)
        assertEquals(
            BitwardenMeta.LastSyncResult.Success,
            database.metaQueries.getByAccountId(ACCOUNT_ID).executeAsOne().data_.lastSyncResult,
        )
    }

    @Test
    fun `SyncByBitwardenTokenV2Impl keeps pending hard delete local when server rejects deletes`() = runTest {
        val server = UploadTestServer()
        server.revisionDate = "rev-stable"
        server.seedCipher(testCipherEntity(id = "cipher-remote-1"))
        server.nextCipherBulkDeleteFailure = HttpStatusCode.Forbidden
        server.cipherDeleteFailuresById["cipher-remote-1"] = HttpStatusCode.Forbidden
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

        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T2,
                remoteRevisionDate = T0,
                attachments = emptyList(),
            ).copy(
                service = testService(
                    remoteId = "cipher-remote-1",
                    remoteRevisionDate = T0,
                    deleted = true,
                ),
            )
        database.cipherQueries.insert(
            cipherId = local.cipherId,
            accountId = local.accountId,
            folderId = local.folderId,
            data = local,
            updatedAt = local.revisionDate,
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
            pendingUploadCoordinator = UploadTestPendingUploadCoordinator(),
            watchdog = UploadTestWatchdog,
            markBackupAsDirty = UploadTestMarkBackupAsDirty,
        )

        val syncError = assertFailsWith<IllegalStateException> {
            sync.invoke(user).invoke()
        }

        assertEquals("Sync completed with 1 action failure(s).", syncError.message)
        assertIs<HttpException>(syncError.cause)
        assertEquals(
            listOf(
                HttpMethod.Get to "/api/accounts/revision-date",
                HttpMethod.Get to "/api/sync",
                HttpMethod.Delete to "/api/ciphers/",
                HttpMethod.Delete to "/api/ciphers/cipher-remote-1",
            ),
            server.requests.map { it.method to it.path },
        )
        val saved =
            database.cipherQueries
                .getByCipherId("cipher-local-1")
                .executeAsOne()
                .data_
        assertEquals("cipher-remote-1", saved.service.remote?.id)
        assertEquals(true, saved.service.deleted)
        assertEquals(HttpStatusCode.Forbidden.value, saved.service.error?.code)
        assertEquals(setOf("cipher-remote-1"), server.ciphers.keys)
        assertIs<BitwardenMeta.LastSyncResult.Failure>(
            database.metaQueries.getByAccountId(ACCOUNT_ID).executeAsOne().data_.lastSyncResult,
        )
    }

    @Test
    fun `production CipherSyncOps bulk delete separates soft and hard delete requests`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val softDeleted = testCipher(
            localId = "cipher-soft-local",
            remoteId = "cipher-soft-remote",
            localRevisionDate = T1,
            remoteRevisionDate = T0,
            attachments = emptyList(),
        )
        val hardDeleted = testCipher(
            localId = "cipher-hard-local",
            remoteId = "cipher-hard-remote",
            localRevisionDate = T1,
            remoteRevisionDate = T0,
            attachments = emptyList(),
        ).copy(
            service = testService(
                remoteId = "cipher-hard-remote",
                remoteRevisionDate = T0,
                deleted = true,
            ),
        )

        fixture.ops.bulkDeleteOnServer(
            listOf(
                softDeleted to "cipher-soft-remote",
                hardDeleted to "cipher-hard-remote",
            ),
        )

        assertEquals(
            listOf(
                HttpMethod.Put to "/api/ciphers/delete",
                HttpMethod.Delete to "/api/ciphers/",
            ),
            server.requests.map { it.method to it.path },
        )
        assertEquals("""{"ids":["cipher-soft-remote"]}""", server.requests[0].body)
        assertEquals("""{"ids":["cipher-hard-remote"]}""", server.requests[1].body)
    }

    @Test
    fun `production CipherSyncOps bulk delete sends only soft delete request when all entries are live`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local = testCipher(
            localId = "cipher-local",
            remoteId = "cipher-remote",
            localRevisionDate = T1,
            remoteRevisionDate = T0,
            attachments = emptyList(),
        )

        fixture.ops.bulkDeleteOnServer(listOf(local to "cipher-remote"))

        assertEquals(listOf(HttpMethod.Put to "/api/ciphers/delete"), server.requests.map { it.method to it.path })
        assertEquals("""{"ids":["cipher-remote"]}""", server.requests.single().body)
    }

    @Test
    fun `production CipherSyncOps bulk delete sends only hard delete request when all entries are deleted`() = runTest {
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local = testCipher(
            localId = "cipher-local",
            remoteId = "cipher-remote",
            localRevisionDate = T1,
            remoteRevisionDate = T0,
            attachments = emptyList(),
        ).copy(
            service = testService(
                remoteId = "cipher-remote",
                remoteRevisionDate = T0,
                deleted = true,
            ),
        )

        fixture.ops.bulkDeleteOnServer(listOf(local to "cipher-remote"))

        assertEquals(listOf(HttpMethod.Delete to "/api/ciphers/"), server.requests.map { it.method to it.path })
        assertEquals("""{"ids":["cipher-remote"]}""", server.requests.single().body)
    }
}

private suspend fun assertProductionCipherPendingAttachmentClearedOnFailure(
    message: String,
    expectedStatusCode: HttpStatusCode = HttpStatusCode.BadRequest,
    pendingUploadRemoteId: String? = null,
    configureServer: (UploadTestServer) -> Unit,
) {
    withTempUploadFile("terminal cipher upload bytes") { file, pendingUpload ->
        val currentPendingUpload =
            pendingUploadRemoteId
                ?.let { remoteId -> pendingUpload.copy(remoteId = remoteId) }
                ?: pendingUpload
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = listOf(
                    BitwardenCipher.Attachment.Local(
                        id = "attachment-local-1",
                        url = "file://${file.absolutePath}",
                        fileName = "terminal-cipher.bin",
                        size = pendingUpload.encryptedSize,
                        keyBase64 =
                            fixture.base64Service.encodeToString(
                                ByteArray(64) { index -> (index + 31).toByte() },
                            ),
                        pendingUpload = currentPendingUpload,
                    ),
                ),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
            )
        val remote =
            local.copy(attachments = emptyList())
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
        server.seedCipher(remote)
        fixture.database.cipherQueries.insert(
            cipherId = local.cipherId,
            accountId = local.accountId,
            folderId = local.folderId,
            data = local,
            updatedAt = local.revisionDate,
        )
        configureServer(server)

        val failure = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = remote,
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

        val saved = fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
        assertEquals(emptyList(), failure.partialRemoteLocal?.pendingLocalAttachments().orEmpty(), message)
        assertEquals(emptyList(), saved.pendingLocalAttachments(), message)
        assertEquals(emptyList(), saved.attachments, message)
        assertEquals(expectedStatusCode.value, saved.service.error?.code, message)
        assertTrue(saved.service.error?.message?.contains(message) == true, message)
        assertEquals(listOf(currentPendingUpload), fixture.coordinator.deleteCalls, message)
        assertEquals(emptyList(), fixture.coordinator.markUploadedCalls, message)
        assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies, message)
    }
}

private suspend fun assertProductionCipherPendingAttachmentPreservedOnFailure(
    message: String,
    expectedStatusCode: HttpStatusCode,
) {
    withTempUploadFile("generic cipher upload bytes") { file, pendingUpload ->
        val server = UploadTestServer()
        val fixture = createProductionCipherOpsFixture(server)
        val local =
            testCipher(
                localId = "cipher-local-1",
                remoteId = "cipher-remote-1",
                localRevisionDate = T0,
                remoteRevisionDate = T0,
                attachments = listOf(
                    BitwardenCipher.Attachment.Local(
                        id = "attachment-local-1",
                        url = "file://${file.absolutePath}",
                        fileName = "generic-cipher.bin",
                        size = pendingUpload.encryptedSize,
                        keyBase64 =
                            fixture.base64Service.encodeToString(
                                ByteArray(64) { index -> (index + 31).toByte() },
                            ),
                        pendingUpload = pendingUpload,
                    ),
                ),
            ).copy(
                keyBase64 = fixture.cipherKeyBase64(),
            )
        val remote =
            local.copy(attachments = emptyList())
                .toEncryptedCipherEntity(
                    crypto = fixture.crypto,
                    base64Service = fixture.base64Service,
                )
        server.seedCipher(remote)
        fixture.database.cipherQueries.insert(
            cipherId = local.cipherId,
            accountId = local.accountId,
            folderId = local.folderId,
            data = local,
            updatedAt = local.revisionDate,
        )
        server.nextCipherAttachmentUploadFailure = expectedStatusCode
        server.nextCipherAttachmentUploadFailureMessage = message

        val failure = assertCipherFailure {
            fixture.ops.pushToServer(
                local = local,
                server = remote,
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

        val saved = fixture.database.cipherQueries.getByCipherId("cipher-local-1").executeAsOne().data_
        val savedAttachment = assertIs<BitwardenCipher.Attachment.Local>(saved.attachments.single(), message)
        assertEquals(pendingUpload.copy(remoteId = "attachment-created-1"), savedAttachment.pendingUpload, message)
        assertEquals(expectedStatusCode.value, saved.service.error?.code, message)
        assertEquals(emptyList(), fixture.coordinator.deleteCalls, message)
        assertEquals(emptyList(), fixture.coordinator.markUploadedCalls, message)
        assertEquals(emptyMap(), server.uploadedCipherAttachmentBodies, message)
        assertEquals(listOf("attachment-created-1"), server.deletedCipherAttachmentIds, message)
    }
}

private suspend fun runCipherUploadSync(
    server: UploadTestServer,
    store: CipherUploadStore,
    pendingUploadCoordinator: UploadTestPendingUploadCoordinator,
) = SyncCoordinator().safeSyncEntityType(
    EntitySyncConfig(
        name = "ciphers",
        strategy = CipherSyncStrategy(
            remoteFolderIdToLocalId = { it },
        ),
        localEntities = store.locals.values.toList(),
        serverEntities = server.ciphers.values.toList(),
        ops = CipherUploadIntegrationOps(server, store, pendingUploadCoordinator),
    ),
)

private fun passwordHistory(
    password: String,
    lastUsedDate: Instant,
) = BitwardenCipher.Login.PasswordHistory(
    password = password,
    lastUsedDate = lastUsedDate,
)

private fun BitwardenCipher.toLoginCipher(
    keyBase64: String,
    password: String,
    passwordRevisionDate: Instant,
    passwordHistory: List<BitwardenCipher.Login.PasswordHistory>,
    totp: String? = null,
) = copy(
    keyBase64 = keyBase64,
    type = BitwardenCipher.Type.Login,
    secureNote = null,
    login = BitwardenCipher.Login(
        username = "user@example.com",
        password = password,
        passwordRevisionDate = passwordRevisionDate,
        uris = emptyList(),
        totp = totp,
    ),
    passwordHistory = passwordHistory,
)

private data class ProductionCipherOpsFixture(
    val database: Database,
    val crypto: BitwardenCrImpl,
    val cryptoGenerator: CryptoGeneratorJvm,
    val base64Service: Base64ServiceJvm,
    val coordinator: UploadTestPendingUploadCoordinator,
    val ops: CipherSyncOps,
) {
    fun cipherKeyBase64(): String =
        base64Service.encodeToString(ByteArray(64) { index -> (index + 21).toByte() })
}

private fun createProductionCipherOpsFixture(
    server: UploadTestServer,
    coordinator: UploadTestPendingUploadCoordinator = UploadTestPendingUploadCoordinator(),
): ProductionCipherOpsFixture {
    val database = createUploadTestDatabase()
    val cryptoGenerator = CryptoGeneratorJvm()
    val base64Service = Base64ServiceJvm()
    val crypto = createUploadTestCrypto(
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
    )
    val ops = CipherSyncOps(
        accountId = ACCOUNT_ID,
        db = database,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        getPasswordStrength = UploadTestPasswordStrength,
        logRepository = UploadTestLogRepository,
        httpClient = server.client,
        env = server.env,
        token = server.token,
        ciphersApi = server.env.api.ciphers,
        encryptedFor = "profile-1",
        remoteToLocalFolders = emptyMap(),
        localToRemoteFolders = emptyMap(),
        serverFolders = emptyList(),
        pendingUploadCoordinator = coordinator,
    )
    return ProductionCipherOpsFixture(
        database = database,
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        base64Service = base64Service,
        coordinator = coordinator,
        ops = ops,
    )
}

private class CancellingDecodeBitwardenCr(
    private val delegate: BitwardenCr,
) : BitwardenCr {
    override val base64Service: Base64Service
        get() = delegate.base64Service

    override fun decoder(key: BitwardenCrKey) =
        if (key == BitwardenCrKey.UserToken) {
            { _: String -> throw CancellationException("cancel decrypt") }
        } else {
            delegate.decoder(key)
        }

    override fun encoder(key: BitwardenCrKey) =
        delegate.encoder(key)

    override fun cta(
        env: BitwardenCrCta.BitwardenCrCtaEnv,
        mode: BitwardenCrCta.Mode,
    ) = BitwardenCrCta(
        crypto = this,
        env = env,
        mode = mode,
    )
}

private class CipherUploadStore(
    initial: BitwardenCipher,
) {
    val locals = linkedMapOf(initial.cipherId to initial)
}

private class CipherUploadIntegrationOps(
    private val server: UploadTestServer,
    private val store: CipherUploadStore,
    private val pendingUploadCoordinator: UploadTestPendingUploadCoordinator,
) : EntitySyncOps<BitwardenCipher, CipherEntity> {
    override suspend fun readLocal(localId: String): BitwardenCipher? = store.locals[localId]

    override suspend fun insertOrUpdateLocally(entries: List<Pair<CipherEntity, BitwardenCipher?>>) = Unit

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<CipherEntity, BitwardenCipher>>,
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
        local: BitwardenCipher,
        previousLocal: BitwardenCipher?,
    ) {
        store.locals[local.cipherId] = local
        val referencedUploads = local.pendingLocalAttachments()
            .mapNotNull { it.pendingUpload }
            .toSet()
        val obsoleteUploads = previousLocal
            ?.pendingLocalAttachments()
            .orEmpty()
            .mapNotNull { it.pendingUpload }
            .filter { pendingUpload ->
                referencedUploads.none { it.path == pendingUpload.path }
            }
        obsoleteUploads.forEach { pendingUpload ->
            pendingUploadCoordinator.delete(pendingUpload)
        }
    }

    override suspend fun pushToServer(
        local: BitwardenCipher,
        server: CipherEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val remoteCipherId = requireNotNull(local.service.remote?.id ?: server?.id)
        val api = this.server.env.api.ciphers.focus(remoteCipherId)
        var localWithReservations = local
        val uploadedRemoteAttachmentIdsByLocalId = linkedMapOf<String, String>()

        local.pendingRemoteAttachmentDeletionIds().forEach { attachmentId ->
            api.attachments.delete(
                httpClient = this.server.client,
                env = this.server.env,
                token = this.server.token,
                id = attachmentId,
            )
        }

        local.pendingLocalAttachments().forEach { pendingAttachment ->
            val originalPendingUpload = requireNotNull(pendingAttachment.pendingUpload)
            val reservationCreatedInThisSync = originalPendingUpload.remoteId == null
            if (reservationCreatedInThisSync) {
                val reservation = api.attachments.postV2(
                    httpClient = this.server.client,
                    env = this.server.env,
                    token = this.server.token,
                    body = CipherAttachmentCreateRequest(
                        key = requireNotNull(pendingAttachment.keyBase64),
                        fileName = pendingAttachment.fileName,
                        fileSize = originalPendingUpload.encryptedSize,
                        adminRequest = false,
                        lastKnownRevisionDate = localWithReservations.service.remote?.revisionDate,
                    ),
                )
                localWithReservations =
                    localWithReservations.withPendingAttachmentRemoteId(
                        localAttachmentId = pendingAttachment.id,
                        remoteAttachmentId = reservation.requiredAttachmentId,
                    )
            }

            val reservedAttachment =
                localWithReservations.pendingLocalAttachments()
                    .single { it.id == pendingAttachment.id }
            val reservedPendingUpload = requireNotNull(reservedAttachment.pendingUpload)
            var remoteAttachmentId = requireNotNull(reservedPendingUpload.remoteId)
            if (!pendingUploadCoordinator.isUploaded(reservedPendingUpload)) {
                var uploadTarget =
                    if (originalPendingUpload.remoteId != null) {
                        runCatching {
                            api.attachments.focus(remoteAttachmentId).renew(
                                httpClient = this.server.client,
                                env = this.server.env,
                                token = this.server.token,
                            )
                        }.getOrElse {
                            val reservation = api.attachments.postV2(
                                httpClient = this.server.client,
                                env = this.server.env,
                                token = this.server.token,
                                body = CipherAttachmentCreateRequest(
                                    key = requireNotNull(pendingAttachment.keyBase64),
                                    fileName = pendingAttachment.fileName,
                                    fileSize = reservedPendingUpload.encryptedSize,
                                    adminRequest = false,
                                    lastKnownRevisionDate = localWithReservations.service.remote?.revisionDate,
                                ),
                            )
                            remoteAttachmentId = reservation.requiredAttachmentId
                            localWithReservations =
                                localWithReservations.withPendingAttachmentRemoteId(
                                    localAttachmentId = pendingAttachment.id,
                                    remoteAttachmentId = remoteAttachmentId,
                                )
                            reservation
                        }
                    } else {
                        this.server.cipherAttachmentReservation(remoteCipherId, remoteAttachmentId)
                    }

                try {
                    uploadCipherAttachment(
                        httpClient = this.server.client,
                        env = this.server.env,
                        token = this.server.token,
                        target = uploadTarget.uploadTarget,
                        fileName = pendingAttachment.fileName,
                        filePath = reservedPendingUpload.path,
                        fileLength = reservedPendingUpload.encryptedSize,
                    )
                } catch (e: Throwable) {
                    if (reservationCreatedInThisSync) {
                        withContext(NonCancellable) {
                            runCatching {
                                api.attachments.delete(
                                    httpClient = this@CipherUploadIntegrationOps.server.client,
                                    env = this@CipherUploadIntegrationOps.server.env,
                                    token = this@CipherUploadIntegrationOps.server.token,
                                    id = remoteAttachmentId,
                                )
                            }
                        }
                    }
                    coroutineContext.ensureActive()
                    if (e is CancellationException) throw e
                    return RemoteWriteOutcome.Failure(localWithReservations, e)
                }
                pendingUploadCoordinator.markUploaded(reservedPendingUpload)
            }
            uploadedRemoteAttachmentIdsByLocalId[pendingAttachment.id] = remoteAttachmentId
        }

        val refreshed = api.get(
            httpClient = this.server.client,
            env = this.server.env,
            token = this.server.token,
        )
        val remoteAttachments = refreshed.attachments.orEmpty().map { it.toLocalRemoteAttachment() }
        val reconciliation = localWithReservations.reconcilePendingLocalAttachments(
            remoteAttachments = remoteAttachments,
            uploadedRemoteAttachmentIdsByLocalId = uploadedRemoteAttachmentIdsByLocalId,
        )
        return RemoteWriteOutcome.Upsert(
            reconciliation.cipher.copy(
                revisionDate = refreshed.revisionDate,
                service = BitwardenService(
                    remote = BitwardenService.Remote(
                        id = refreshed.id,
                        revisionDate = refreshed.revisionDate,
                        deletedDate = refreshed.deletedDate,
                    ),
                    version = BitwardenService.VERSION,
                ),
            ),
        )
    }

    override suspend fun markRemoteFailure(
        local: BitwardenCipher,
        remoteLocal: BitwardenCipher?,
        error: Throwable,
    ): BitwardenCipher {
        val localWithReservations =
            remoteLocal
                ?.let(local::mergePendingAttachmentRemoteIdsFrom)
                ?: local
        return super.markRemoteFailure(
            local = localWithReservations,
            remoteLocal = null,
            error = error,
        )
    }

    override suspend fun deleteOnServer(
        local: BitwardenCipher,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenCipher> = error("unused")

    override suspend fun mergeConflict(
        local: BitwardenCipher,
        server: CipherEntity,
    ): RemoteWriteOutcome<BitwardenCipher> = pushToServer(local, server, force = true)
}
