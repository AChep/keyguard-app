package com.artemchep.keyguard.provider.bitwarden.sync.v2.ops

import arrow.optics.dsl.notNull
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.patch.ModelDiffUtil
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.fields
import com.artemchep.keyguard.core.store.bitwarden.getMergeRules
import com.artemchep.keyguard.core.store.bitwarden.getUrlChecksumBase64
import com.artemchep.keyguard.core.store.bitwarden.hasPendingAttachmentMutations
import com.artemchep.keyguard.core.store.bitwarden.login
import com.artemchep.keyguard.core.store.bitwarden.mergePendingAttachmentRemoteIdsFrom
import com.artemchep.keyguard.core.store.bitwarden.name
import com.artemchep.keyguard.core.store.bitwarden.pendingAttachmentUploads
import com.artemchep.keyguard.core.store.bitwarden.pendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.pendingRemoteAttachmentDeletionIds
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingLocalAttachments
import com.artemchep.keyguard.core.store.bitwarden.tags
import com.artemchep.keyguard.core.store.bitwarden.uris
import com.artemchep.keyguard.core.store.bitwarden.withPendingAttachmentRemoteId
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.SyncEngine
import com.artemchep.keyguard.provider.bitwarden.api.builder.ServerEnvApi
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadCipherAttachment
import com.artemchep.keyguard.provider.bitwarden.api.builder.create
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.postV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.renew
import com.artemchep.keyguard.provider.bitwarden.api.builder.restore
import com.artemchep.keyguard.provider.bitwarden.api.builder.trash
import com.artemchep.keyguard.provider.bitwarden.api.merge
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.CryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.decodeSymmetricOrThrow
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.CipherEntity
import com.artemchep.keyguard.provider.bitwarden.entity.FolderEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherAttachmentCreateRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherDeleteRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherRestoreRequest
import com.artemchep.keyguard.provider.bitwarden.entity.request.CipherUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.of
import com.artemchep.keyguard.provider.bitwarden.sync.v2.BitwardenSyncV2Diagnostics
import com.artemchep.keyguard.provider.bitwarden.sync.v2.hasHttpStatusCode
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.BulkRemoteOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import com.artemchep.keyguard.provider.bitwarden.usecase.util.with3WayMergePasswordHistoryOrNull
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sync operations for Bitwarden ciphers.
 *
 * Ciphers are the most complex entity type: they support
 * **dual-key crypto** (per-item key + org/user key),
 * **three-way merge** via [ModelDiffUtil] with [BitwardenCipher.getMergeRules],
 * and **bulk server operations** ([BulkRemoteOps]).
 *
 * **Push flow** for modifications (restore → PUT → trash → GET):
 * 1. If the cipher was trashed, restore it first.
 * 2. If data changed, PUT the updated cipher.
 * 3. If the cipher should be trashed, trash it.
 * 4. Final GET to refresh local state.
 *
 * Intermediate server responses are captured as [partialRemoteLocal]
 * so that metadata can be preserved even if a later step fails.
 *
 * **Bulk operations** separate soft-delete (trash) from hard-delete
 * based on [BitwardenService.deleted] and use [CipherDeleteRequest]
 * / [CipherRestoreRequest] request models.
 *
 * @param encryptedFor the Bitwarden profile ID used for the
 *   `encryptedFor` field in cipher create/update requests.
 * @param remoteToLocalFolders maps remote folder IDs to local IDs.
 * @param localToRemoteFolders maps local folder IDs to remote IDs.
 * @param serverFolders the folder list from the sync response,
 *   used to verify folder existence during decoding.
 */
class CipherSyncOps(
    private val accountId: String,
    private val db: Database,
    private val crypto: BitwardenCr,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val getPasswordStrength: GetPasswordStrength,
    private val logRepository: LogRepository,
    private val httpClient: HttpClient,
    private val env: ServerEnv,
    private val token: String,
    private val ciphersApi: ServerEnvApi.Ciphers,
    private val encryptedFor: String,
    private val remoteToLocalFolders: Map<String, String>,
    private val localToRemoteFolders: Map<String, String?>,
    private val serverFolders: List<FolderEntity>,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val diagnostics: BitwardenSyncV2Diagnostics = BitwardenSyncV2Diagnostics.NoOp,
) : EntitySyncOps<BitwardenCipher, CipherEntity>,
    BulkRemoteOps<BitwardenCipher> {
    companion object {
        private const val TAG = "CipherSyncOps"
    }

    private val mergeRules by lazy { BitwardenCipher.getMergeRules() }

    override suspend fun readLocal(localId: String): BitwardenCipher? =
        db.cipherQueries
            .getByCipherId(cipherId = localId)
            .executeAsOneOrNull()
            ?.data_

    override suspend fun insertOrUpdateLocally(entries: List<Pair<CipherEntity, BitwardenCipher?>>) {
        val now = Clock.System.now()
        val decoded =
            entries.map { (server, local) ->
                decodeServerCipherOrFallback(
                    server = server,
                    local = local,
                    now = now,
                )
            }
        saveCiphersLocally(decoded)
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<CipherEntity, BitwardenCipher>>,
    ): LocalUpdateResult {
        val now = Clock.System.now()
        val decoded =
            entries.map { entry ->
                entry to decodeServerCipherOrFallback(
                    server = entry.server,
                    local = entry.initialLocal,
                    now = now,
                )
            }
        return updateCiphersLocally(decoded)
    }

    private suspend fun decodeServerCipherOrFallback(
        server: CipherEntity,
        local: BitwardenCipher?,
        now: Instant,
    ): BitwardenCipher =
        decodeRemoteOrFallback(
            decode = {
                decodeServerCipher(server, local)
            },
            fallback = { e ->
                recordCipherDecodeFailure(
                    server = server,
                    error = e,
                    isMerge = false,
                )
                val service = server.toDecodingFailedService(now)
                val model =
                    local?.copy(service = service)
                        ?: unsupportedCipher(
                            server = server,
                            service = service,
                        )
                merge(model, local, getPasswordStrength)
            },
        )

    private fun CipherEntity.toDecodingFailedService(now: Instant) =
        createDecodingFailedService(
            now = now,
            remoteId = id,
            revisionDate = revisionDate,
            deletedDate = deletedDate,
        )

    private fun unsupportedCipher(
        server: CipherEntity,
        service: BitwardenService,
    ): BitwardenCipher =
        BitwardenCipher(
            accountId = accountId,
            cipherId = cryptoGenerator.uuid(),
            folderId = server.folderId?.let { remoteToLocalFolders[it] },
            organizationId = server.organizationId,
            revisionDate = server.revisionDate,
            archivedDate = server.archivedDate,
            deletedDate = server.deletedDate,
            service = service,
            name = "⚠️ Unsupported Item",
            notes = null,
            favorite = false,
            reprompt = BitwardenCipher.RepromptType.None,
            type = BitwardenCipher.Type.Card,
        )

    private fun recordCipherDecodeFailure(
        server: CipherEntity,
        error: Throwable,
        isMerge: Boolean,
    ) {
        val logObj = mapOf("name" to server.name?.take(2))
        val message =
            if (isMerge) {
                "Failed to decrypt a cipher during merge. Structure: $logObj"
            } else {
                "Failed to decrypt a cipher. Structure: $logObj"
            }
        val logE =
            SyncEngine.DecodeVaultException(
                message = message,
                e = error,
            )
        recordException(logE)
    }

    private fun updateCiphersLocally(
        decoded: List<Pair<LocalUpdateEntry<CipherEntity, BitwardenCipher>, BitwardenCipher>>,
    ): LocalUpdateResult {
        var applied = 0
        var skipped = 0
        db.cipherQueries.transaction {
            decoded.forEach { (entry, cipher) ->
                val current =
                    db.cipherQueries
                        .getByCipherId(cipherId = entry.localId)
                        .executeAsOneOrNull()
                        ?.data_
                if (entry.writeIfCurrent(current) { insertCipher(cipher) }) {
                    applied++
                } else {
                    skipped++
                }
            }
        }
        return LocalUpdateResult(
            applied = applied,
            skipped = skipped,
        )
    }

    private fun saveCiphersLocally(ciphers: List<BitwardenCipher>) {
        db.cipherQueries.transaction {
            ciphers.forEach(::insertCipher)
        }
    }

    private fun insertCipher(cipher: BitwardenCipher) {
        db.cipherQueries.insert(
            cipherId = cipher.cipherId,
            accountId = cipher.accountId,
            folderId = cipher.folderId,
            data = cipher,
            updatedAt = cipher.revisionDate,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        val pendingUploadsToDelete = mutableListOf<PendingUploadFile>()
        db.cipherQueries.transaction {
            localIds.forEach { cipherId ->
                val cipher =
                    db.cipherQueries
                        .getByCipherId(cipherId = cipherId)
                        .executeAsOneOrNull()
                        ?.data_
                if (cipher != null) {
                    pendingUploadsToDelete += cipher.pendingAttachmentUploads()
                }
                db.cipherQueries.deleteByCipherId(
                    cipherId = cipherId,
                )
            }
        }
        pendingUploadsToDelete
            .distinctBy { it.path }
            .forEach { pendingUpload ->
                runCatching {
                    pendingUploadCoordinator.delete(pendingUpload)
                }
            }
    }

    override suspend fun saveLocal(
        local: BitwardenCipher,
        previousLocal: BitwardenCipher?,
    ) {
        saveCipherLocally(local)
        deleteObsoletePendingUploadsAfterSave(
            previous = previousLocal,
            saved = local,
        )
    }

    override suspend fun pushToServer(
        local: BitwardenCipher,
        server: CipherEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val update = local.toCipherUpdate()
        val now = Clock.System.now()
        var partialRemoteLocal: BitwardenCipher? = null
        val cipherResponse =
            try {
                pushCipherUpdate(
                    update = update,
                    local = local,
                    force = force,
                    updatePartialRemoteLocal = { partialRemoteLocal = it },
                )
            } catch (e: Throwable) {
                e.throwIfCancellation()
                return RemoteWriteOutcome.Failure(partialRemoteLocal, e)
            }

        var decodedResponse =
            try {
                decodeServerCipherForPush(
                    server = cipherResponse,
                    local = local,
                )
            } catch (e: Throwable) {
                e.throwIfCancellation()
                val partial =
                    partialRemoteLocal
                        ?: buildDecodeFailurePartial(
                            now = now,
                            server = cipherResponse,
                            local = local,
                        )
                return RemoteWriteOutcome.Failure(partial, e)
            }
        partialRemoteLocal = decodedResponse
        try {
            decodedResponse = syncPendingAttachmentMutations(
                local = decodedResponse,
                updatePartialRemoteLocal = { partialRemoteLocal = it },
            )
        } catch (e: Throwable) {
            e.throwIfCancellation()
            return RemoteWriteOutcome.Failure(partialRemoteLocal, e)
        }
        return RemoteWriteOutcome.Upsert(decodedResponse)
    }

    private fun BitwardenCipher.toCipherUpdate(): CipherUpdate {
        val model = normalizedForPush()
        val itemKey = model.keyBase64?.let(base64Service::decode)
        val (itemCrypto, globalCrypto) =
            getCipherCodecPair(
                mode = BitwardenCrCta.Mode.ENCRYPT,
                key = itemKey,
                organizationId = model.organizationId,
            )
        val encryptedCipher =
            model.transform(
                itemCrypto = itemCrypto,
                globalCrypto = globalCrypto,
            )
        return CipherUpdate.of(
            model = encryptedCipher,
            folders = localToRemoteFolders,
            encryptedFor = this@CipherSyncOps.encryptedFor,
        )
    }

    private fun BitwardenCipher.normalizedForPush(): BitwardenCipher {
        val withSafeName = BitwardenCipher.name.modify(this) { it.orEmpty() }
        val withUriChecksums =
            BitwardenCipher.login.notNull.uris.modify(withSafeName) { uris ->
                uris.map { uri ->
                    if (uri.uriChecksumBase64 != null) return@map uri
                    val uriChecksumBase64 =
                        BitwardenCipher.Login.Uri.getUrlChecksumBase64(
                            cryptoGenerator = cryptoGenerator,
                            base64Service = base64Service,
                            uri = uri.uri,
                        )
                    uri.copy(uriChecksumBase64 = uriChecksumBase64)
                }
            }
        val withTagsAsFields =
            BitwardenCipher.fields.modify(withUriChecksums) { fields ->
                fields + withUriChecksums.tags.map { tag ->
                    BitwardenCipher.Field(
                        name = "Tag",
                        value = tag.name,
                        type = BitwardenCipher.Field.Type.Text,
                    )
                }
            }
        return BitwardenCipher.tags.set(withTagsAsFields, emptyList())
    }

    private suspend fun pushCipherUpdate(
        update: CipherUpdate,
        local: BitwardenCipher,
        force: Boolean,
        updatePartialRemoteLocal: (BitwardenCipher) -> Unit,
    ): CipherEntity =
        when (update) {
            is CipherUpdate.Modify ->
                pushModifiedCipher(
                    update = update,
                    local = local,
                    force = force,
                    updatePartialRemoteLocal = updatePartialRemoteLocal,
                )

            is CipherUpdate.Create ->
                createUserCipher(update)

            is CipherUpdate.CreateInOrg ->
                createOrganizationCipher(update)
        }

    private suspend fun pushModifiedCipher(
        update: CipherUpdate.Modify,
        local: BitwardenCipher,
        force: Boolean,
        updatePartialRemoteLocal: (BitwardenCipher) -> Unit,
    ): CipherEntity {
        val cipherApi = ciphersApi.focus(update.cipherId)
        var cipherRequest = update.cipherRequest

        suspend fun handleIntermediateResponse(cipherEntity: CipherEntity) {
            cipherRequest =
                update.cipherRequest.copy(
                    lastKnownRevisionDate = cipherEntity.revisionDate,
                )
            updatePartialRemoteLocal(
                decodeServerCipherForPush(
                    server = cipherEntity,
                    local = local,
                ),
            )
        }

        val isTrashed = update.source.deletedDate != null
        val wasTrashed = update.source.service.remote?.deletedDate != null
        val hasChanged = update.hasChanged(force)
        if (isTrashed == wasTrashed && !hasChanged) {
            return getCipher(cipherApi)
        }

        if (wasTrashed) {
            val restoredCipher =
                cipherApi.restore(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    cipherRequest = cipherRequest,
                )
            handleIntermediateResponse(restoredCipher)
        }

        val putCipher =
            if (hasChanged) {
                cipherApi.put(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    body = cipherRequest,
                )
            } else {
                null
            }

        if (isTrashed) {
            if (putCipher != null) {
                handleIntermediateResponse(putCipher)
            }
            trashCipher(cipherApi)
        }
        return getCipher(cipherApi)
    }

    private fun CipherUpdate.Modify.hasChanged(force: Boolean): Boolean =
        source.service.remote?.revisionDate != source.revisionDate || force

    private suspend fun getCipher(cipherApi: ServerEnvApi.Ciphers.Cipher): CipherEntity =
        cipherApi.get(
            httpClient = httpClient,
            env = env,
            token = token,
        )

    private suspend fun trashCipher(cipherApi: ServerEnvApi.Ciphers.Cipher) {
        try {
            cipherApi.trash(
                httpClient = httpClient,
                env = env,
                token = token,
            )
        } catch (e: HttpException) {
            if (
                e.statusCode.value !in 200..299 ||
                e.cause !is NoTransformationFoundException
            ) {
                throw e
            }
        }
    }

    private suspend fun createUserCipher(update: CipherUpdate.Create): CipherEntity {
        require(update.cipherRequest.organizationId == null) {
            "To create a cipher in the organization, you must use a special API call."
        }
        return ciphersApi.post(
            httpClient = httpClient,
            env = env,
            token = token,
            body = update.cipherRequest,
        )
    }

    private suspend fun createOrganizationCipher(update: CipherUpdate.CreateInOrg): CipherEntity {
        require(update.cipherRequest.cipher.organizationId != null) {
            "To create a cipher in the user's vault, you must use a special API call."
        }
        return ciphersApi.create(
            httpClient = httpClient,
            env = env,
            token = token,
            body = update.cipherRequest,
        )
    }

    private suspend fun syncPendingAttachmentMutations(
        local: BitwardenCipher,
        updatePartialRemoteLocal: (BitwardenCipher) -> Unit,
    ): BitwardenCipher {
        if (local.deletedDate != null || !local.hasPendingAttachmentMutations()) {
            return local
        }

        var currentLocal = local
        val remoteCipherId =
            requireNotNull(currentLocal.service.remote?.id) {
                "Bitwarden cipher id must be available before attachment sync."
            }
        val cipherApi = ciphersApi.focus(remoteCipherId)

        currentLocal.pendingRemoteAttachmentDeletionIds()
            .forEach { attachmentId ->
                diagnostics.cipherAttachmentRemoteDeletionStarted(
                    cipherLocalId = currentLocal.cipherId,
                    cipherRemoteId = remoteCipherId,
                    attachmentRemoteId = attachmentId,
                )
                cipherApi.attachments.delete(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    id = attachmentId,
                )
                diagnostics.cipherAttachmentRemoteDeletionCompleted(
                    cipherLocalId = currentLocal.cipherId,
                    cipherRemoteId = remoteCipherId,
                    attachmentRemoteId = attachmentId,
                )
                val refreshedResponse =
                    runCatching {
                        cipherApi.get(
                            httpClient = httpClient,
                            env = env,
                            token = token,
                        )
                    }.getOrElse { e ->
                        if (e.hasHttpStatusCode(HttpStatusCode.NotFound)) {
                            return@forEach
                        }
                        throw e
                    }
                currentLocal =
                    decodeServerCipherForPush(
                        server = refreshedResponse,
                        local = currentLocal,
                    )
                updatePartialRemoteLocal(currentLocal)
            }

        val uploadedRemoteAttachmentIdsByLocalId = mutableMapOf<String, String>()
        currentLocal.pendingLocalAttachments()
            .forEach { attachment ->
                val pendingUpload = attachment.pendingUpload
                    ?: return@forEach
                val remoteId = pendingUpload.remoteId
                    ?: return@forEach
                if (pendingUploadCoordinator.isUploaded(pendingUpload)) {
                    uploadedRemoteAttachmentIdsByLocalId[attachment.id] = remoteId
                }
            }
        if (uploadedRemoteAttachmentIdsByLocalId.isNotEmpty()) {
            val remoteAttachments =
                currentLocal.attachments
                    .filterIsInstance<BitwardenCipher.Attachment.Remote>()
            val reconciliation =
                currentLocal.reconcilePendingLocalAttachments(
                    remoteAttachments = remoteAttachments,
                    uploadedRemoteAttachmentIdsByLocalId = uploadedRemoteAttachmentIdsByLocalId,
                )
            currentLocal = reconciliation.cipher
            updatePartialRemoteLocal(currentLocal)
        }

        while (true) {
            val localAttachment =
                currentLocal.pendingLocalAttachments()
                    .firstOrNull()
                    ?: break
            val pendingUpload =
                requireNotNull(localAttachment.pendingUpload) {
                    "A pending local cipher attachment must contain staged upload metadata."
                }
            val itemKey = currentLocal.keyBase64?.let(base64Service::decode)
            val (itemCrypto, _) =
                getCipherCodecPair(
                    mode = BitwardenCrCta.Mode.ENCRYPT,
                    key = itemKey,
                    organizationId = currentLocal.organizationId,
                )
            val attachmentCreateRequest =
                CipherAttachmentCreateRequest.of(
                    cipher = currentLocal,
                    attachment = localAttachment,
                    itemCrypto = itemCrypto,
                )
            val remoteAttachmentId = pendingUpload.remoteId
            val createResponse =
                try {
                    diagnostics.cipherAttachmentSlotRequested(
                        cipherLocalId = currentLocal.cipherId,
                        cipherRemoteId = remoteCipherId,
                        attachmentLocalId = localAttachment.id,
                        requestedRemoteId = remoteAttachmentId,
                    )
                    if (remoteAttachmentId != null) {
                        runCatching {
                            cipherApi.attachments.focus(remoteAttachmentId).renew(
                                httpClient = httpClient,
                                env = env,
                                token = token,
                            )
                        }.getOrElse { e ->
                            if (!e.hasHttpStatusCode(HttpStatusCode.NotFound)) {
                                throw e
                            }
                            cipherApi.attachments.postV2(
                                httpClient = httpClient,
                                env = env,
                                token = token,
                                body = attachmentCreateRequest,
                            )
                        }
                    } else {
                        cipherApi.attachments.postV2(
                            httpClient = httpClient,
                            env = env,
                            token = token,
                            body = attachmentCreateRequest,
                        )
                    }
                } catch (e: Throwable) {
                    if (e.isNonRetryableCipherAttachmentUploadError()) {
                        currentLocal = currentLocal.withoutPendingLocalAttachmentUpload(localAttachment.id)
                        updatePartialRemoteLocal(currentLocal)
                    }
                    throw e
                }
            val reservedRemoteAttachmentId =
                remoteAttachmentId
                    ?: createResponse.requiredAttachmentId
            diagnostics.cipherAttachmentSlotReserved(
                cipherLocalId = currentLocal.cipherId,
                cipherRemoteId = remoteCipherId,
                attachmentLocalId = localAttachment.id,
                attachmentRemoteId = reservedRemoteAttachmentId,
            )
            if (pendingUpload.remoteId == null) {
                currentLocal =
                    currentLocal.withPendingAttachmentRemoteId(
                        localAttachmentId = localAttachment.id,
                        remoteAttachmentId = reservedRemoteAttachmentId,
                    )
                updatePartialRemoteLocal(currentLocal)
            }

            try {
                diagnostics.cipherAttachmentUploadStarted(
                    cipherLocalId = currentLocal.cipherId,
                    cipherRemoteId = remoteCipherId,
                    attachmentLocalId = localAttachment.id,
                    attachmentRemoteId = reservedRemoteAttachmentId,
                    encryptedSize = pendingUpload.encryptedSize,
                )
                uploadCipherAttachment(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    target = createResponse.uploadTarget,
                    fileName = attachmentCreateRequest.fileName,
                    filePath = pendingUpload.path,
                    fileLength = pendingUpload.encryptedSize,
                )
                diagnostics.cipherAttachmentUploadCompleted(
                    cipherLocalId = currentLocal.cipherId,
                    cipherRemoteId = remoteCipherId,
                    attachmentLocalId = localAttachment.id,
                    attachmentRemoteId = reservedRemoteAttachmentId,
                )
            } catch (e: Throwable) {
                val cleanupSucceeded =
                    if (remoteAttachmentId == null) {
                        withContext(NonCancellable) {
                            // This sync reserved a new remote attachment slot. Always
                            // try to remove it before honoring cancellation so retries
                            // do not accumulate orphaned attachment placeholders.
                            runCatching {
                                cipherApi.attachments.delete(
                                    httpClient = httpClient,
                                    env = env,
                                    token = token,
                                    id = reservedRemoteAttachmentId,
                                )
                            }
                        }.fold(
                            onSuccess = { true },
                            onFailure = { cleanupError ->
                                cleanupError.hasHttpStatusCode(HttpStatusCode.NotFound)
                            },
                        )
                    } else {
                        true
                    }
                if (e.isNonRetryableCipherAttachmentUploadError() && cleanupSucceeded) {
                    currentLocal = currentLocal.withoutPendingLocalAttachmentUpload(localAttachment.id)
                    updatePartialRemoteLocal(currentLocal)
                }
                diagnostics.cipherAttachmentUploadFailed(
                    cipherLocalId = currentLocal.cipherId,
                    cipherRemoteId = remoteCipherId,
                    attachmentLocalId = localAttachment.id,
                    attachmentRemoteId = reservedRemoteAttachmentId,
                    cleanupSucceeded = cleanupSucceeded,
                    error = e,
                )
                e.throwIfCancellation()
                coroutineContext.ensureActive()
                throw e
            }

            pendingUploadCoordinator.markUploaded(
                pendingUpload.copy(
                    remoteId = reservedRemoteAttachmentId,
                ),
            )
            diagnostics.cipherAttachmentMarkedUploaded(
                cipherLocalId = currentLocal.cipherId,
                cipherRemoteId = remoteCipherId,
                attachmentLocalId = localAttachment.id,
                attachmentRemoteId = reservedRemoteAttachmentId,
            )

            val refreshedResponse =
                runCatching {
                    cipherApi.get(
                        httpClient = httpClient,
                        env = env,
                        token = token,
                    )
                }.getOrElse { e ->
                    e.throwIfCancellation()
                    createResponse.cipherResponse
                        ?: createResponse.cipherMiniResponse
                        ?: throw e
                }
            val refreshedRemote =
                decodeServerCipherRawForPush(
                    server = refreshedResponse,
                    local = currentLocal,
                )
            val reconciliation =
                currentLocal.reconcilePendingLocalAttachments(
                    remoteAttachments =
                        refreshedRemote.attachments
                            .filterIsInstance<BitwardenCipher.Attachment.Remote>(),
                    uploadedRemoteAttachmentIdsByLocalId =
                        mapOf(localAttachment.id to reservedRemoteAttachmentId),
                )
            requireNotNull(reconciliation.replacementsByLocalId[localAttachment.id]) {
                "Failed to locate the uploaded cipher attachment on remote."
            }
            diagnostics.cipherAttachmentReconciled(
                cipherLocalId = currentLocal.cipherId,
                cipherRemoteId = remoteCipherId,
                attachmentLocalId = localAttachment.id,
                attachmentRemoteId = reservedRemoteAttachmentId,
            )
            currentLocal =
                merge(
                    remote = refreshedRemote,
                    local = reconciliation.cipher,
                    getPasswordStrength = getPasswordStrength,
                )
            updatePartialRemoteLocal(currentLocal)
        }

        return currentLocal
    }

    override suspend fun deleteOnServer(
        local: BitwardenCipher,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenCipher> {
        ciphersApi.focus(serverId).delete(
            httpClient = httpClient,
            env = env,
            token = token,
        )
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenCipher,
        server: CipherEntity,
    ): RemoteWriteOutcome<BitwardenCipher> {
        val now = Clock.System.now()

        diagnostics.cipherMergeStarted(
            localId = local.cipherId,
            remoteId = server.id,
            remoteRevisionDate = server.revisionDate,
            localRevisionDate = local.revisionDate,
            localRemoteRevisionDate = local.service.remote?.revisionDate,
        )

        val remoteDecoded =
            try {
                decodeServerCipher(server, local)
            } catch (e: Throwable) {
                e.throwIfCancellation()
                recordCipherDecodeFailure(
                    server = server,
                    error = e,
                    isMerge = true,
                )

                // If the remote cipher cannot be decoded during conflict
                // resolution, preserve the local cipher content and only
                // replace sync metadata with a decode-failure marker for the
                // remote revision.
                val service = server.toDecodingFailedService(now)
                val fallback = local.copy(service = service)
                val fallbackMerged = merge(fallback, local, getPasswordStrength)
                return RemoteWriteOutcome.Upsert(fallbackMerged)
            }

        val base = local.remoteEntity
        if (base != null) {
            val diffUtil = ModelDiffUtil()
            val merged =
                with(diffUtil) {
                    mergeRules.merge(base, local, remoteDecoded)
                } as BitwardenCipher?

            if (merged != null) {
                diagnostics.cipherMergeSucceeded(
                    localId = local.cipherId,
                    remoteId = remoteDecoded.cipherId,
                )

                var finalMerged = merged
                // TODO: Password history merge re-introduces deleted password-history
                //  entries during conflict merge. A remote/user deletion can be undone
                //  and uploaded again if the local side still has that base entry.
                finalMerged = finalMerged.with3WayMergePasswordHistoryOrNull(
                    at = now,
                    remoteDecoded,
                    local,
                ) ?: finalMerged
                finalMerged = finalMerged.copy(revisionDate = now)
                return pushToServer(
                    local = finalMerged,
                    server = server,
                    force = false,
                )
            }
        }

        diagnostics.cipherMergeFallback(
            localId = local.cipherId,
            remoteId = remoteDecoded.cipherId,
        )

        var finalFallback = remoteDecoded
        finalFallback = finalFallback.with3WayMergePasswordHistoryOrNull(
            at = now,
            local,
        )
            // We did not do any changes to the model with the password history
            // merge, so we can skip pushing the update back to a server.
            ?: return RemoteWriteOutcome.Upsert(remoteDecoded)
        finalFallback = finalFallback.copy(revisionDate = now)
        return pushToServer(
            local = finalFallback,
            server = server,
            force = false,
        )
    }

    // ---------------------------------------------------------------
    // Bulk remote operations
    // ---------------------------------------------------------------

    /**
     * Bulk-deletes ciphers on the server. Separates entries into
     * soft-delete (trash) and hard-delete based on [BitwardenService.deleted],
     * then issues the appropriate bulk API call for each group.
     */
    override suspend fun bulkDeleteOnServer(entries: List<Pair<BitwardenCipher, String>>) {
        val softDeleteIds = mutableListOf<String>()
        val hardDeleteIds = mutableListOf<String>()
        for ((local, serverId) in entries) {
            val isLocallyDeleted = local.service.deleted
            if (isLocallyDeleted) {
                hardDeleteIds.add(serverId)
            } else {
                softDeleteIds.add(serverId)
            }
        }
        if (softDeleteIds.isNotEmpty()) {
            ciphersApi.trash(
                httpClient = httpClient,
                env = env,
                token = token,
                body = CipherDeleteRequest(ids = softDeleteIds),
            )
        }
        if (hardDeleteIds.isNotEmpty()) {
            ciphersApi.delete(
                httpClient = httpClient,
                env = env,
                token = token,
                body = CipherDeleteRequest(ids = hardDeleteIds),
            )
        }
    }

    /** Restores previously trashed ciphers on the server in bulk. */
    override suspend fun bulkRestoreOnServer(serverIds: List<String>) {
        ciphersApi.restore(
            httpClient = httpClient,
            env = env,
            token = token,
            body = CipherRestoreRequest(ids = serverIds),
        )
    }

    /** Moves ciphers to the server trash in bulk. */
    override suspend fun bulkTrashOnServer(serverIds: List<String>) {
        ciphersApi.trash(
            httpClient = httpClient,
            env = env,
            token = token,
            body = CipherDeleteRequest(ids = serverIds),
        )
    }

    // ---------------------------------------------------------------
    // Decode helpers
    // ---------------------------------------------------------------

    /**
     * Decrypts a server cipher entity into a local [BitwardenCipher],
     * mapping remote folder IDs to local ones and merging computed
     * fields (password strength, etc.) via [merge].
     */
    private suspend fun decodeServerCipher(
        server: CipherEntity,
        local: BitwardenCipher?,
    ): BitwardenCipher {
        val cipherId = local?.cipherId ?: cryptoGenerator.uuid()
        val decoded =
            decodeServerCipherRaw(
                server = server,
                cipherId = cipherId,
            )
        return merge(decoded, local, getPasswordStrength)
    }

    private fun decodeServerCipherRaw(
        server: CipherEntity,
        cipherId: String,
    ): BitwardenCipher {
        val (itemCrypto, globalCrypto) =
            getCipherCodecPairFromEncrypted(
                mode = BitwardenCrCta.Mode.DECRYPT,
                keyCipherText = server.key,
                organizationId = server.organizationId,
            )
        val folderId =
            server.folderId?.let { remoteFolderId ->
                val localFolderId = remoteToLocalFolders[remoteFolderId]
                if (localFolderId != null) return@let localFolderId

                val folderExists = serverFolders.any { it.id == remoteFolderId }
                if (folderExists) {
                    // The folder exists but we don't have a local mapping.
                }
                null
            }
        return BitwardenCipher
            .encrypted(
                accountId = accountId,
                cipherId = cipherId,
                folderId = folderId,
                entity = server,
            )
            .transform(itemCrypto, globalCrypto)
    }

    /**
     * Decodes a server response after a push operation. The
     * response's encrypted item key is the source of truth because
     * some endpoints return the persisted cipher rather than the
     * just-submitted request body.
     */
    private suspend fun decodeServerCipherForPush(
        server: CipherEntity,
        local: BitwardenCipher,
    ): BitwardenCipher =
        merge(
            decodeServerCipherRawForPush(server, local),
            local,
            getPasswordStrength,
        )

    private fun decodeServerCipherRawForPush(
        server: CipherEntity,
        local: BitwardenCipher,
    ): BitwardenCipher =
        decodeServerCipherRaw(
            server = server,
            cipherId = local.cipherId,
        )

    /**
     * Creates a partial local entity with decode-failure metadata
     * when the server response cannot be decrypted after a push.
     */
    private fun buildDecodeFailurePartial(
        now: Instant,
        server: CipherEntity,
        local: BitwardenCipher,
    ): BitwardenCipher {
        val service = server.toDecodingFailedService(now)
        return local.copy(service = service)
    }

    // ---------------------------------------------------------------
    // Local DB helpers
    // ---------------------------------------------------------------

    private fun saveCipherLocally(cipher: BitwardenCipher) {
        saveCiphersLocally(listOf(cipher))
    }

    private suspend fun deleteObsoletePendingUploadsAfterSave(
        previous: BitwardenCipher?,
        saved: BitwardenCipher,
    ) {
        val previousPendingUploads = previous?.pendingAttachmentUploads().orEmpty()
        if (previousPendingUploads.isEmpty()) return
        val referencedPendingUploads = saved.pendingAttachmentUploads()
        val obsoletePendingUploads =
            previousPendingUploads
                .filterNot { pendingUpload ->
                    referencedPendingUploads.any { it.path == pendingUpload.path }
                }
        if (obsoletePendingUploads.isEmpty()) return

        obsoletePendingUploads.forEach { pendingUpload ->
            runCatching {
                pendingUploadCoordinator.delete(pendingUpload)
            }
        }
    }

    // ---------------------------------------------------------------
    // Error handling
    // ---------------------------------------------------------------

    override fun mergeRemoteSuccessIntoChangedLocal(
        current: BitwardenCipher,
        remoteLocal: BitwardenCipher,
    ): BitwardenCipher {
        val service = current.service.copy(
            remote = remoteLocal.service.remote,
            error = null,
            version = remoteLocal.service.version,
        )
        return current
            .copy(
                service = service,
                remoteEntity = remoteLocal.remoteEntity ?: remoteLocal,
            )
            .mergePendingAttachmentRemoteIdsFrom(remoteLocal)
    }

    override suspend fun markRemoteFailure(
        local: BitwardenCipher,
        remoteLocal: BitwardenCipher?,
        error: Throwable,
    ): BitwardenCipher {
        logRepository.add(
            TAG,
            "Failed to push cipher: ${error.safeCipherPushFailureSummary()}",
            LogLevel.INFO,
        )
        val localWithRemote =
            when {
                error.isNonRetryableCipherAttachmentUploadError() && remoteLocal != null ->
                    remoteLocal

                remoteLocal != null ->
                    mergeRemoteSuccessIntoChangedLocal(
                        current = local,
                        remoteLocal = remoteLocal,
                    )

                error.isNonRetryableCipherAttachmentUploadError() ->
                    local.withoutPendingLocalAttachmentUploads()

                else ->
                    local
            }
        return super.markRemoteFailure(localWithRemote, remoteLocal, error)
    }

    private fun BitwardenCipher.withoutPendingLocalAttachmentUploads(): BitwardenCipher =
        copy(
            attachments =
                attachments.filterNot { attachment ->
                    attachment is BitwardenCipher.Attachment.Local &&
                        attachment.pendingUpload != null
                },
        )

    private fun BitwardenCipher.withoutPendingLocalAttachmentUpload(
        localAttachmentId: String,
    ): BitwardenCipher =
        copy(
            attachments =
                attachments.filterNot { attachment ->
                    attachment is BitwardenCipher.Attachment.Local &&
                        attachment.id == localAttachmentId &&
                        attachment.pendingUpload != null
                },
        )

    // ---------------------------------------------------------------
    // Crypto helpers
    // ---------------------------------------------------------------

    private fun getCipherCodecPair(
        mode: BitwardenCrCta.Mode,
        key: ByteArray?,
        organizationId: String?,
    ): Pair<BitwardenCrCta, BitwardenCrCta> =
        buildCipherCodecPair(crypto, mode, key, organizationId)

    private fun getCipherCodecPairFromEncrypted(
        mode: BitwardenCrCta.Mode,
        keyCipherText: String?,
        organizationId: String?,
    ): Pair<BitwardenCrCta, BitwardenCrCta> =
        buildCipherCodecPairFromEncrypted(crypto, mode, keyCipherText, organizationId)
}

private fun Throwable.safeCipherPushFailureSummary(): String {
    val type = this::class.simpleName ?: "Throwable"
    val httpException =
        generateSequence(this) { it.cause }
            .filterIsInstance<HttpException>()
            .firstOrNull()
    return buildString {
        append("type=")
        append(type)
        if (httpException != null) {
            append(", http_status=")
            append(httpException.statusCode.value)
        }
    }
}

/**
 * Builds the (item, global) codec pair for a cipher from a raw key.
 *
 * The global codec uses the organization's key when [organizationId]
 * is non-null, otherwise the user's key. The item codec uses the
 * per-cipher symmetric key when [key] is non-null, otherwise falls
 * back to the global codec.
 */
internal fun buildCipherCodecPair(
    crypto: BitwardenCr,
    mode: BitwardenCrCta.Mode,
    key: ByteArray?,
    organizationId: String?,
): Pair<BitwardenCrCta, BitwardenCrCta> {
    val globalCrypto =
        buildSyncCodec(
            crypto = crypto,
            mode = mode,
            key = syncKeyForOrganization(organizationId),
        )
    val itemCrypto =
        if (key != null) {
            val symmetricCryptoKey = CryptoKey.decodeSymmetricOrThrow(key)
            buildSyncCodec(
                crypto = crypto,
                mode = mode,
                key = BitwardenCrKey.CryptoKey(
                    symmetricCryptoKey = symmetricCryptoKey,
                ),
            )
        } else {
            globalCrypto
        }
    return itemCrypto to globalCrypto
}

/**
 * Builds the (item, global) codec pair for a cipher from an
 * encrypted key cipher-text. Decrypts the key using the appropriate
 * key (org or user), then delegates to [buildCipherCodecPair].
 */
internal fun buildCipherCodecPairFromEncrypted(
    crypto: BitwardenCr,
    mode: BitwardenCrCta.Mode,
    keyCipherText: String?,
    organizationId: String?,
): Pair<BitwardenCrCta, BitwardenCrCta> {
    val key =
        if (keyCipherText != null) {
            crypto
                .decoder(syncKeyForOrganization(organizationId))(keyCipherText)
                .data
        } else {
            null
        }
    return buildCipherCodecPair(
        crypto = crypto,
        mode = mode,
        key = key,
        organizationId = organizationId,
    )
}
