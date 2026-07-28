package com.artemchep.keyguard.provider.bitwarden.sync.v2

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCollection
import com.artemchep.keyguard.core.store.bitwarden.BitwardenFolder
import com.artemchep.keyguard.core.store.bitwarden.BitwardenMeta
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOrganization
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.api.builder.sanitize
import com.artemchep.keyguard.provider.bitwarden.api.builder.tryGetErrorMessageBitwardenApi
import com.artemchep.keyguard.provider.bitwarden.api.builder.tryGetErrorMessageCloudflareApi
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.UntrustedProfileException
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.fetchServerRevisionDateOrNull
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.hasPendingLocalWork
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.requireTrustedProfileId
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.requireTrustedProfileSecurityStamp
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.requiresAuthenticationForSyncFailure
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.shouldSkipFullSyncForRevision
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.ActionFailure
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.EntityTypeOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncAction
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncExecutionResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.SyncResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.core.requireCleanForRevisionCache
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncConfig
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.SyncCoordinator
import com.artemchep.keyguard.provider.bitwarden.sync.v2.strategy.EntitySyncStrategy
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SyncCoordinatorAndRevisionV2Test {
    @Test
    fun `coordinator completes one entity type through plan and executor`() = runTest {
        val local = localOnly(localId = "local-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val outcome =
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "test",
                    strategy = TestSyncStrategy,
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = ops,
                ),
            )

        val completed = outcome as EntityTypeOutcome.Completed
        assertEquals(1, completed.result.succeeded)
        assertEquals(
            listOf(
                SyncCall.PushToServer(
                    localId = "local-1",
                    serverId = null,
                    force = false,
                ),
            ),
            ops.calls.filterIsInstance<SyncCall.PushToServer>(),
        )
    }

    @Test
    fun `safe coordinator isolates entity type failure`() = runTest {
        val local = localOnly(localId = "local-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        ops.onPushToServer = { _, _, _ ->
            error("remote write unavailable")
        }

        val outcome =
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "test",
                    strategy = TestSyncStrategy,
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = ops,
                ),
            )

        val completed = outcome as EntityTypeOutcome.Completed
        assertEquals(1, completed.result.failures.size)
        assertEquals("remote write unavailable", completed.result.failures.single().error.message)
    }

    @Test
    fun `plan builder exception becomes failed entity type outcome`() = runTest {
        val failure =
            IllegalStateException("metadata extraction failed")
        val local = localOnly(localId = "local-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val throwingStrategy =
            object : EntitySyncStrategy<TestLocal, TestServer> by TestSyncStrategy {
                override fun toLocalItemMeta(entity: TestLocal) = throw failure
            }

        val outcome =
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "test",
                    strategy = throwingStrategy,
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = ops,
                ),
            )

        assertEquals(failure, assertIs<EntityTypeOutcome.Failed>(outcome).error)
    }

    @Test
    fun `coordinator propagates cancellation instead of wrapping failed outcome`() = runTest {
        val local = localOnly(localId = "local-1", revisionDate = T1)
        val ops = TestEntitySyncOps(listOf(local))
        val throwingStrategy =
            object : EntitySyncStrategy<TestLocal, TestServer> by TestSyncStrategy {
                override fun toLocalItemMeta(entity: TestLocal): Nothing =
                    throw CancellationException("cancelled")
            }

        assertFailsWith<CancellationException> {
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "test",
                    strategy = throwingStrategy,
                    localEntities = listOf(local),
                    serverEntities = emptyList(),
                    ops = ops,
                ),
            )
        }
    }

    @Test
    fun `one entity type failure does not prevent another from completing`() = runTest {
        val failing = localOnly(localId = "local-failing", revisionDate = T1)
        val succeeding = localOnly(localId = "local-success", revisionDate = T1)
        val failingOps = TestEntitySyncOps(listOf(failing))
        val failingStrategy =
            object : EntitySyncStrategy<TestLocal, TestServer> by TestSyncStrategy {
                override fun toLocalItemMeta(entity: TestLocal): Nothing =
                    error("metadata extraction failed")
            }
        val succeedingOps = TestEntitySyncOps(listOf(succeeding))

        val failed =
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "failing",
                    strategy = failingStrategy,
                    localEntities = listOf(failing),
                    serverEntities = emptyList(),
                    ops = failingOps,
                ),
            )
        val completed =
            SyncCoordinator().safeSyncEntityType(
                EntitySyncConfig(
                    name = "succeeding",
                    strategy = TestSyncStrategy,
                    localEntities = listOf(succeeding),
                    serverEntities = emptyList(),
                    ops = succeedingOps,
                ),
            )

        assertEquals("metadata extraction failed", assertIs<EntityTypeOutcome.Failed>(failed).error.message)
        assertEquals(1, assertIs<EntityTypeOutcome.Completed>(completed).result.succeeded)
    }

    @Test
    fun `revision-date skip is allowed only for clean matching account metadata`() {
        val clean = BitwardenMeta(
            accountId = "account-1",
            lastSyncResult = BitwardenMeta.LastSyncResult.Success,
            lastServerRevisionDate = "rev-1",
            lastSyncServiceVersion = BitwardenService.VERSION,
        )

        assertTrue(
            shouldSkipFullSyncForRevision(
                existingMeta = clean,
                serverRevisionDate = "rev-1",
                hasPendingLocalWork = false,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = null,
                serverRevisionDate = "rev-1",
                hasPendingLocalWork = false,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = clean,
                serverRevisionDate = null,
                hasPendingLocalWork = false,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = clean,
                serverRevisionDate = "rev-2",
                hasPendingLocalWork = false,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = clean,
                serverRevisionDate = "rev-1",
                hasPendingLocalWork = true,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = clean.copy(lastSyncServiceVersion = BitwardenService.VERSION - 1),
                serverRevisionDate = "rev-1",
                hasPendingLocalWork = false,
            ),
        )
        assertFalse(
            shouldSkipFullSyncForRevision(
                existingMeta = clean.copy(
                    lastSyncResult =
                        BitwardenMeta.LastSyncResult.Failure(
                            timestamp = T0,
                            reason = "previous sync failed",
                        ),
                ),
                serverRevisionDate = "rev-1",
                hasPendingLocalWork = false,
            ),
        )
    }

    @Test
    fun `pending upload and local repair markers suppress revision-date skip`() {
        assertTrue(
            hasPendingLocalWork(
                ciphers = listOf(testCipher()),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = listOf(testCipherWithPendingAttachment()),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = emptyList(),
                folders = emptyList(),
                sends = listOf(testSendWithPendingFile()),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = listOf(
                    testCipher(
                        login =
                            BitwardenCipher.Login(
                                passwordRevisionDate = T0_PLUS_NANOS,
                                uris = emptyList(),
                            ),
                    ),
                ),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = emptyList(),
                folders = listOf(
                    testFolder(
                        service =
                            testService(
                                remoteId = "remote-folder-1",
                                remoteRevisionDate = T0,
                                error =
                                    BitwardenService.Error(
                                        code = BitwardenService.Error.CODE_UNKNOWN,
                                        revisionDate = T0,
                                    ),
                            ),
                    ),
                ),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = emptyList(),
                folders = emptyList(),
                sends = emptyList(),
                collections = listOf(
                    testCollection(
                        service =
                            testService(
                                remoteId = "remote-collection-1",
                                remoteRevisionDate = T0,
                                version = BitwardenService.VERSION - 1,
                            ),
                    ),
                ),
                organizations = emptyList(),
            ),
        )
        assertTrue(
            hasPendingLocalWork(
                ciphers = emptyList(),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = listOf(
                    testOrganization(
                        service = testService(),
                    ),
                ),
            ),
        )
        assertFalse(
            hasPendingLocalWork(
                ciphers = listOf(
                    testCipher(
                        service =
                            testService(
                                remoteId = "remote-cipher-1",
                                remoteRevisionDate = T0,
                                error =
                                    BitwardenService.Error(
                                        code = HttpStatusCode.Unauthorized.value,
                                        revisionDate = T0,
                                    ),
                            ),
                        remoteEntity = testCipher(),
                    ),
                ),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
        assertFalse(
            hasPendingLocalWork(
                ciphers = emptyList(),
                folders = emptyList(),
                sends = emptyList(),
                collections = emptyList(),
                organizations = emptyList(),
            ),
        )
    }

    @Test
    fun `pending cipher work prevents loading later entity types`() {
        val loaded = mutableListOf<String>()

        val hasPendingLocalWork =
            hasPendingLocalWork(
                ciphers = {
                    loaded += "ciphers"
                    sequenceOf(testCipher())
                },
                folders = {
                    loaded += "folders"
                    emptySequence()
                },
                sends = {
                    loaded += "sends"
                    emptySequence()
                },
                collections = {
                    loaded += "collections"
                    emptySequence()
                },
                organizations = {
                    loaded += "organizations"
                    emptySequence()
                },
            )

        assertTrue(hasPendingLocalWork)
        assertEquals(listOf("ciphers"), loaded)
    }

    @Test
    fun `pending folder work prevents loading sends collections and organizations`() {
        val loaded = mutableListOf<String>()

        val hasPendingLocalWork =
            hasPendingLocalWork(
                ciphers = {
                    loaded += "ciphers"
                    emptySequence()
                },
                folders = {
                    loaded += "folders"
                    sequenceOf(
                        testFolder(
                            service =
                                testService(
                                    remoteId = "remote-folder-1",
                                    remoteRevisionDate = T0,
                                    error =
                                        BitwardenService.Error(
                                            code = BitwardenService.Error.CODE_UNKNOWN,
                                            revisionDate = T0,
                                        ),
                                ),
                        ),
                    )
                },
                sends = {
                    loaded += "sends"
                    emptySequence()
                },
                collections = {
                    loaded += "collections"
                    emptySequence()
                },
                organizations = {
                    loaded += "organizations"
                    emptySequence()
                },
            )

        assertTrue(hasPendingLocalWork)
        assertEquals(listOf("ciphers", "folders"), loaded)
    }

    @Test
    fun `pending send work prevents loading collections and organizations`() {
        val loaded = mutableListOf<String>()

        val hasPendingLocalWork =
            hasPendingLocalWork(
                ciphers = {
                    loaded += "ciphers"
                    emptySequence()
                },
                folders = {
                    loaded += "folders"
                    emptySequence()
                },
                sends = {
                    loaded += "sends"
                    sequenceOf(testSendWithPendingFile())
                },
                collections = {
                    loaded += "collections"
                    emptySequence()
                },
                organizations = {
                    loaded += "organizations"
                    emptySequence()
                },
            )

        assertTrue(hasPendingLocalWork)
        assertEquals(listOf("ciphers", "folders", "sends"), loaded)
    }

    @Test
    fun `no pending local work loads all entity types`() {
        val loaded = mutableListOf<String>()

        val hasPendingLocalWork =
            hasPendingLocalWork(
                ciphers = {
                    loaded += "ciphers"
                    emptySequence()
                },
                folders = {
                    loaded += "folders"
                    emptySequence()
                },
                sends = {
                    loaded += "sends"
                    emptySequence()
                },
                collections = {
                    loaded += "collections"
                    emptySequence()
                },
                organizations = {
                    loaded += "organizations"
                    emptySequence()
                },
            )

        assertFalse(hasPendingLocalWork)
        assertEquals(
            listOf("ciphers", "folders", "sends", "collections", "organizations"),
            loaded,
        )
    }

    @Test
    fun `revision-date fetch falls back to full sync on non-cancellation failures`() = runTest {
        assertEquals(
            "rev-1",
            fetchServerRevisionDateOrNull { "rev-1" },
        )
        assertNull(
            fetchServerRevisionDateOrNull {
                throw IllegalStateException("endpoint unavailable")
            },
        )
        assertFailsWith<CancellationException> {
            fetchServerRevisionDateOrNull {
                throw CancellationException("cancelled")
            }
        }
    }

    @Test
    fun `sync failure authentication classifier only marks auth and permission failures`() {
        assertTrue(
            requiresAuthenticationForSyncFailure(
                HttpException(
                    statusCode = HttpStatusCode.Unauthorized,
                    m = "expired",
                    e = null,
                ),
            ),
        )
        assertTrue(
            requiresAuthenticationForSyncFailure(
                HttpException(
                    statusCode = HttpStatusCode.Forbidden,
                    m = "forbidden",
                    e = null,
                ),
            ),
        )
        assertTrue(
            requiresAuthenticationForSyncFailure(
                IllegalStateException(
                    "Sync completed with 1 action failure(s).",
                    HttpException(
                        statusCode = HttpStatusCode.Forbidden,
                        m = "forbidden",
                        e = null,
                    ),
                ),
            ),
        )
        assertFalse(
            requiresAuthenticationForSyncFailure(
                HttpException(
                    statusCode = HttpStatusCode.TooManyRequests,
                    m = "rate limited",
                    e = null,
                ),
            ),
        )
        assertFalse(
            requiresAuthenticationForSyncFailure(
                UntrustedProfileException("profile trust failed"),
            ),
        )
        assertFalse(
            requiresAuthenticationForSyncFailure(
                IllegalStateException("network unavailable"),
            ),
        )
    }

    @Test
    fun `profile security stamp mismatch is rejected before profile overwrite`() {
        requireTrustedProfileSecurityStamp(
            existingSecurityStamp = null,
            remoteSecurityStamp = "remote-stamp",
        )
        requireTrustedProfileSecurityStamp(
            existingSecurityStamp = "trusted-stamp",
            remoteSecurityStamp = "trusted-stamp",
        )

        assertFailsWith<UntrustedProfileException> {
            requireTrustedProfileSecurityStamp(
                existingSecurityStamp = "trusted-stamp",
                remoteSecurityStamp = "other-stamp",
            )
        }
    }

    @Test
    fun `profile id mismatch is rejected before profile overwrite`() {
        requireTrustedProfileId(
            existingProfileId = null,
            remoteProfileId = "remote-profile",
        )
        requireTrustedProfileId(
            existingProfileId = "trusted-profile",
            remoteProfileId = "trusted-profile",
        )

        assertFailsWith<UntrustedProfileException> {
            requireTrustedProfileId(
                existingProfileId = "trusted-profile",
                remoteProfileId = "other-profile",
            )
        }
    }

    @Test
    fun `clean sync result is required before caching revision date`() {
        SyncResult(
            outcomes =
                mapOf(
                    "ciphers" to
                        EntityTypeOutcome.Completed(
                            SyncExecutionResult(succeeded = 1),
                        ),
                ),
        ).requireCleanForRevisionCache()
        assertTrue(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(succeeded = 1),
                            ),
                    ),
            ).canCacheServerRevisionDate,
        )

        assertFailsWith<IllegalStateException> {
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(skipped = 1),
                            ),
                    ),
            ).requireCleanForRevisionCache()
        }
        assertFailsWith<IllegalStateException> {
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(
                                    failures =
                                        listOf(
                                            ActionFailure(
                                                action = SyncAction.InsertLocally("remote-1"),
                                                error = IllegalStateException("boom"),
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ).requireCleanForRevisionCache()
        }
        assertFailsWith<IllegalStateException> {
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Failed(
                                IllegalStateException("entity type failed"),
                            ),
                    ),
            ).requireCleanForRevisionCache()
        }
        SyncResult(
            outcomes =
                mapOf(
                    "ciphers" to
                        EntityTypeOutcome.Completed(
                            SyncExecutionResult(
                                staleServerEntities = 1,
                            ),
                        ),
                ),
        ).requireCleanForRevisionCache()
        assertFalse(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(
                                    staleServerEntities = 1,
                                ),
                            ),
                    ),
            ).canCacheServerRevisionDate,
        )
        val firstFailure = IllegalStateException("first entity type failed")
        val thrown =
            assertFailsWith<IllegalStateException> {
                SyncResult(
                    outcomes =
                        mapOf(
                            "folders" to EntityTypeOutcome.Failed(firstFailure),
                            "ciphers" to EntityTypeOutcome.Failed(IllegalStateException("second entity type failed")),
                        ),
                ).requireCleanForRevisionCache()
            }
        assertEquals(firstFailure, thrown)

        SyncResult(outcomes = emptyMap()).requireCleanForRevisionCache()
    }

    @Test
    fun `sync result is fully successful only when completed outcomes are clean`() {
        assertTrue(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(succeeded = 1),
                            ),
                    ),
            ).isFullySuccessful,
        )

        assertFalse(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(skipped = 1),
                            ),
                    ),
            ).isFullySuccessful,
        )
        val staleResult =
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(staleServerEntities = 1),
                            ),
                    ),
            )
        assertTrue(staleResult.isFullySuccessful)
        assertFalse(staleResult.canCacheServerRevisionDate)
        assertFalse(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Completed(
                                SyncExecutionResult(
                                    failures =
                                        listOf(
                                            ActionFailure(
                                                action = SyncAction.InsertLocally("remote-1"),
                                                error = IllegalStateException("boom"),
                                            ),
                                        ),
                                ),
                            ),
                    ),
            ).isFullySuccessful,
        )
        assertFalse(
            SyncResult(
                outcomes =
                    mapOf(
                        "ciphers" to
                            EntityTypeOutcome.Failed(
                                IllegalStateException("entity type failed"),
                            ),
                    ),
            ).isFullySuccessful,
        )
    }

    @Test
    fun `Bitwarden and Cloudflare error JSON messages are extracted without leaking primitive bodies`() {
        val bitwardenJson = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("description", "stale revision")
                },
            )
        }
        val cloudflareJson = buildJsonObject {
            put("message", "rate limited")
        }
        val sensitiveJson = buildJsonObject {
            put("token", "secret-token")
            put("limit", 42)
            put("retry", true)
        }

        assertEquals("stale revision", bitwardenJson.tryGetErrorMessageBitwardenApi())
        assertEquals("rate limited", cloudflareJson.tryGetErrorMessageCloudflareApi())
        assertEquals(
            JsonObject(
                mapOf(
                    "token" to JsonPrimitive("string"),
                    "limit" to JsonPrimitive("number"),
                    "retry" to JsonPrimitive("bool"),
                ),
            ),
            sensitiveJson.sanitize(),
        )
    }
}

private fun testCipher(
    service: BitwardenService =
        testService(
            remoteId = "remote-cipher-1",
            remoteRevisionDate = T0,
        ),
    revisionDate: kotlin.time.Instant = T0,
    login: BitwardenCipher.Login? = null,
    attachments: List<BitwardenCipher.Attachment> = emptyList(),
    remoteEntity: BitwardenCipher? = null,
): BitwardenCipher =
    BitwardenCipher(
        accountId = "account-1",
        cipherId = "cipher-1",
        revisionDate = revisionDate,
        createdDate = T0,
        service = service,
        keyBase64 = "cipher-key",
        name = "Cipher",
        notes = "",
        favorite = false,
        attachments = attachments,
        login = login,
        reprompt = BitwardenCipher.RepromptType.None,
        type = BitwardenCipher.Type.SecureNote,
        secureNote = BitwardenCipher.SecureNote(),
        remoteEntity = remoteEntity,
    )

private fun testFolder(
    service: BitwardenService =
        testService(
            remoteId = "remote-folder-1",
            remoteRevisionDate = T0,
        ),
    revisionDate: kotlin.time.Instant = T0,
): BitwardenFolder =
    BitwardenFolder(
        accountId = "account-1",
        folderId = "folder-1",
        revisionDate = revisionDate,
        service = service,
        name = "Folder",
    )

private fun testCollection(
    service: BitwardenService =
        testService(
            remoteId = "remote-collection-1",
            remoteRevisionDate = T0,
        ),
    revisionDate: kotlin.time.Instant = T0,
): BitwardenCollection =
    BitwardenCollection(
        accountId = "account-1",
        collectionId = "collection-1",
        externalId = null,
        organizationId = "organization-1",
        revisionDate = revisionDate,
        service = service,
        name = "Collection",
        hidePasswords = false,
        readOnly = false,
    )

private fun testOrganization(
    service: BitwardenService =
        testService(
            remoteId = "remote-organization-1",
            remoteRevisionDate = T0,
        ),
    revisionDate: kotlin.time.Instant = T0,
): BitwardenOrganization =
    BitwardenOrganization(
        accountId = "account-1",
        organizationId = "organization-1",
        revisionDate = revisionDate,
        service = service,
        name = "Organization",
    )

private fun testCipherWithPendingAttachment(): BitwardenCipher =
    testCipher(
        remoteEntity =
            testCipher(
                attachments =
                    listOf(
                        BitwardenCipher.Attachment.Remote(
                            id = "remote-existing",
                            url = null,
                            fileName = "old.txt",
                            keyBase64 = "key",
                            size = 10L,
                        ),
                    ),
            ),
        attachments =
            listOf(
                BitwardenCipher.Attachment.Local(
                    id = "local-upload",
                    url = "file:///tmp/new.txt",
                    fileName = "new.txt",
                    size = 10L,
                    keyBase64 = "key",
                    pendingUpload = testPendingUpload(),
                ),
            ),
    )

private fun testSendWithPendingFile(): BitwardenSend =
    BitwardenSend(
        accountId = "account-1",
        sendId = "send-1",
        accessId = "access-1",
        revisionDate = T0,
        createdDate = T0,
        service =
            testService(
                remoteId = "remote-send-1",
                remoteRevisionDate = T0,
            ),
        authType = BitwardenSend.AuthType.None,
        keyBase64 = "send-key",
        name = "Send",
        notes = "",
        accessCount = 0,
        type = BitwardenSend.Type.File,
        file =
            BitwardenSend.File(
                id = "file-1",
                fileName = "send.bin",
                size = 10L,
                pendingUpload = testPendingUpload(),
            ),
    )

private fun testPendingUpload(): PendingUploadFile =
    PendingUploadFile(
        path = "/tmp/pending-upload.bin",
        plainSize = 10L,
        encryptedSize = 42L,
    )

private val T0_PLUS_NANOS = kotlin.time.Instant.parse("2024-01-01T00:00:00.123456789Z")
