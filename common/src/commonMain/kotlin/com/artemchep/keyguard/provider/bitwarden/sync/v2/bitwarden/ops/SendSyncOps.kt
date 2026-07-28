package com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.ops

import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.core.store.bitwarden.BitwardenOptionalStringNullable
import com.artemchep.keyguard.core.store.bitwarden.BitwardenSend
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.core.store.bitwarden.pendingFileUploads
import com.artemchep.keyguard.core.store.bitwarden.reconcilePendingSendFileUpload
import com.artemchep.keyguard.core.store.bitwarden.withPendingUpload
import com.artemchep.keyguard.data.Database
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.api.builder.ServerEnvApi
import com.artemchep.keyguard.provider.bitwarden.api.builder.delete
import com.artemchep.keyguard.provider.bitwarden.api.builder.get
import com.artemchep.keyguard.provider.bitwarden.api.builder.getFileUploadTarget
import com.artemchep.keyguard.provider.bitwarden.api.builder.post
import com.artemchep.keyguard.provider.bitwarden.api.builder.postFileV2
import com.artemchep.keyguard.provider.bitwarden.api.builder.put
import com.artemchep.keyguard.provider.bitwarden.api.builder.removePassword
import com.artemchep.keyguard.provider.bitwarden.api.builder.uploadSendFile
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCr
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrCta
import com.artemchep.keyguard.provider.bitwarden.crypto.BitwardenCrKey
import com.artemchep.keyguard.provider.bitwarden.crypto.CryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.decodeSymmetricOrThrow
import com.artemchep.keyguard.provider.bitwarden.crypto.encrypted
import com.artemchep.keyguard.provider.bitwarden.crypto.makeSendCryptoKey
import com.artemchep.keyguard.provider.bitwarden.crypto.transform
import com.artemchep.keyguard.provider.bitwarden.entity.SendEntity
import com.artemchep.keyguard.provider.bitwarden.entity.request.SendUpdate
import com.artemchep.keyguard.provider.bitwarden.entity.request.of
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.BitwardenSyncDiagnostics
import com.artemchep.keyguard.provider.bitwarden.sync.v2.bitwarden.hasHttpStatusCode
import com.artemchep.keyguard.provider.bitwarden.sync.v2.throwIfCancellation
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.EntitySyncOps
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateEntry
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.LocalUpdateResult
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.RemoteWriteOutcome
import com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline.writeIfCurrent
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadCoordinator
import com.artemchep.keyguard.provider.bitwarden.upload.PendingUploadFile
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Sync operations for Bitwarden Sends.
 *
 * Sends use **dual-key crypto**: each send has a per-item key
 * derived via [makeSendCryptoKey], plus the user's global key
 * for fields encrypted at the account level. The codec pair is
 * constructed by [buildSendCodecPair] / [buildSendCodecPairFromEncrypted].
 *
 * The push flow handles password-removal: if the local changes
 * include clearing the password, a separate `removePassword` call
 * is issued after the PUT.
 */
class SendSyncOps(
    private val accountId: String,
    private val db: Database,
    private val crypto: BitwardenCr,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val httpClient: HttpClient,
    private val env: ServerEnv,
    private val token: String,
    private val sendsApi: ServerEnvApi.Sends,
    private val pendingUploadCoordinator: PendingUploadCoordinator,
    private val diagnostics: BitwardenSyncDiagnostics = BitwardenSyncDiagnostics.NoOp,
) : EntitySyncOps<BitwardenSend, SendEntity> {
    override suspend fun readLocal(localId: String): BitwardenSend? =
        db.sendQueries
            .getBySendId(sendId = localId)
            .executeAsOneOrNull()
            ?.data_

    override suspend fun insertOrUpdateLocally(entries: List<Pair<SendEntity, BitwardenSend?>>) {
        val now = Clock.System.now()
        val decoded =
            entries.map { (server, local) ->
                val send = decodeServerSendOrFallback(
                    server = server,
                    local = local,
                    now = now,
                )
                send to local
            }
        saveSends(decoded.map { it.first })
        decoded.forEach { (send, previous) ->
            if (previous != null) {
                deleteObsoletePendingUploadsAfterSave(
                    previous = previous,
                    saved = send,
                )
            }
        }
    }

    override suspend fun updateLocally(
        entries: List<LocalUpdateEntry<SendEntity, BitwardenSend>>,
    ): LocalUpdateResult {
        val now = Clock.System.now()
        val decoded =
            entries.map { entry ->
                entry to decodeServerSendOrFallback(
                    server = entry.server,
                    local = entry.initialLocal,
                    now = now,
                )
            }
        var applied = 0
        var skipped = 0
        val appliedCleanup = mutableListOf<Pair<BitwardenSend, BitwardenSend>>()
        db.sendQueries.transaction {
            decoded.forEach { (entry, send) ->
                val current =
                    db.sendQueries
                        .getBySendId(sendId = entry.localId)
                        .executeAsOneOrNull()
                        ?.data_
                if (entry.writeIfCurrent(current) { insertSend(send) }) {
                    current?.let { previous ->
                        appliedCleanup += previous to send
                    }
                    applied++
                } else {
                    skipped++
                }
            }
        }
        appliedCleanup.forEach { (previous, saved) ->
            deleteObsoletePendingUploadsAfterSave(
                previous = previous,
                saved = saved,
            )
        }
        return LocalUpdateResult(
            applied = applied,
            skipped = skipped,
        )
    }

    override suspend fun deleteLocally(localIds: List<String>) {
        val pendingUploadsToDelete = mutableListOf<PendingUploadFile>()
        db.sendQueries.transaction {
            localIds.forEach { sendId ->
                val send =
                    db.sendQueries
                        .getBySendId(sendId = sendId)
                        .executeAsOneOrNull()
                        ?.data_
                if (send != null) {
                    pendingUploadsToDelete += send.pendingFileUploads()
                }
                db.sendQueries.deleteBySendId(
                    sendId = sendId,
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
        local: BitwardenSend,
        previousLocal: BitwardenSend?,
    ) {
        saveSends(listOf(local))
        deleteObsoletePendingUploadsAfterSave(
            previous = previousLocal,
            saved = local,
        )
    }

    private fun saveSends(sends: List<BitwardenSend>) {
        db.sendQueries.transaction {
            sends.forEach(::insertSend)
        }
    }

    private fun insertSend(send: BitwardenSend) {
        db.sendQueries.insert(
            accountId = send.accountId,
            sendId = send.sendId,
            data = send,
        )
    }

    override suspend fun pushToServer(
        local: BitwardenSend,
        server: SendEntity?,
        force: Boolean,
    ): RemoteWriteOutcome<BitwardenSend> {
        val itemKey =
            requireNotNull(local.keyBase64)
                .let(base64Service::decode)
        val update = local.toSendUpdate(itemKey)
        var partialRemoteLocal: BitwardenSend? = null
        val decoded =
            try {
                when (update) {
                    is SendUpdate.Modify ->
                        pushModifiedSend(
                            update = update,
                            local = local,
                            server = server,
                            itemKey = itemKey,
                            force = force,
                            updatePartialRemoteLocal = { partialRemoteLocal = it },
                        )

                    is SendUpdate.Create ->
                        pushCreatedSend(
                            update = update,
                            local = local,
                            itemKey = itemKey,
                            updatePartialRemoteLocal = { partialRemoteLocal = it },
                        )
                }
            } catch (e: Throwable) {
                e.throwIfCancellation()
                return RemoteWriteOutcome.Failure(
                    partialRemoteLocal = sendFailurePartial(
                        error = e,
                        local = local,
                        partialRemoteLocal = partialRemoteLocal,
                    ),
                    cause = e,
                )
            }

        return RemoteWriteOutcome.Upsert(decoded)
    }

    private fun BitwardenSend.toSendUpdate(itemKey: ByteArray): SendUpdate {
        val (itemCrypto, globalCrypto) =
            getCodecPair(
                mode = BitwardenCrCta.Mode.ENCRYPT,
                key = itemKey,
            )
        val encryptedSend =
            transform(
                itemCrypto = itemCrypto,
                globalCrypto = globalCrypto,
            )
        return with(cryptoGenerator) {
            with(base64Service) {
                SendUpdate.of(
                    model = encryptedSend,
                    key = itemKey,
                )
            }
        }
    }

    private suspend fun pushModifiedSend(
        update: SendUpdate.Modify,
        local: BitwardenSend,
        server: SendEntity?,
        itemKey: ByteArray,
        force: Boolean,
        updatePartialRemoteLocal: (BitwardenSend?) -> Unit,
    ): BitwardenSend {
        val sendApi = sendsApi.focus(update.sendId)
        val pendingUpload = local.file?.pendingUpload
            ?: return pushModifiedSendWithoutFileUpload(
                update = update,
                local = local,
                itemKey = itemKey,
                force = force,
                sendApi = sendApi,
            )

        val intermediateResponse =
            putSendIfChanged(
                update = update,
                sendApi = sendApi,
                force = force,
            )
        val intermediateLocal =
            intermediateResponse
                ?.let { response ->
                    decodeServerSendForPush(response, local, itemKey)
                        .withPendingUpload(pendingUpload)
                }
                ?: local.withPendingUpload(pendingUpload)
        updatePartialRemoteLocal(intermediateLocal)

        return reconcileUploadedSendFileOrNull(
            sendApi = sendApi,
            pendingUpload = pendingUpload,
            intermediateLocal = intermediateLocal,
            itemKey = itemKey,
        ) ?: uploadPendingSendFile(
            sendApi = sendApi,
            pendingUpload = pendingUpload,
            intermediateLocal = intermediateLocal,
            uploadFileName = intermediateResponse?.file?.fileName
                ?: server?.file?.fileName
                ?: error("Bitwarden send response must contain a file name for upload."),
            itemKey = itemKey,
        )
    }

    private suspend fun pushModifiedSendWithoutFileUpload(
        update: SendUpdate.Modify,
        local: BitwardenSend,
        itemKey: ByteArray,
        force: Boolean,
        sendApi: ServerEnvApi.Sends.Send,
    ): BitwardenSend {
        putSendIfChanged(
            update = update,
            sendApi = sendApi,
            force = force,
        )
        return decodeServerSendForPush(
            response =
                sendApi.get(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                ),
            baseLocal = local,
            itemKey = itemKey,
        )
    }

    private suspend fun putSendIfChanged(
        update: SendUpdate.Modify,
        sendApi: ServerEnvApi.Sends.Send,
        force: Boolean,
    ): SendEntity? {
        if (!update.hasChanged(force)) return null

        var putSend =
            sendApi.put(
                httpClient = httpClient,
                env = env,
                token = token,
                body = update.sendRequest,
            )
        if (update.shouldRemovePassword() && putSend.password != null) {
            putSend =
                sendApi.removePassword(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                )
        }
        return putSend
    }

    private fun SendUpdate.Modify.hasChanged(force: Boolean): Boolean =
        source.service.remote?.revisionDate != source.revisionDate || force

    private fun SendUpdate.Modify.shouldRemovePassword(): Boolean =
        source.changes
            ?.passwordBase64
            ?.let { it is BitwardenOptionalStringNullable.Some && it.value == null } == true

    private suspend fun reconcileUploadedSendFileOrNull(
        sendApi: ServerEnvApi.Sends.Send,
        pendingUpload: PendingUploadFile,
        intermediateLocal: BitwardenSend,
        itemKey: ByteArray,
    ): BitwardenSend? {
        if (!pendingUploadCoordinator.isUploaded(pendingUpload)) return null

        val refreshedResponse =
            sendApi.get(
                httpClient = httpClient,
                env = env,
                token = token,
            )
        val reconciled = reconcileCompletedPendingSendUploadOrNull(
            remote =
                decodeServerSendForPush(
                    response = refreshedResponse,
                    baseLocal = intermediateLocal,
                    itemKey = itemKey,
                ),
            local = intermediateLocal,
        )
        reconciled?.let {
            diagnostics.sendFileReconciled(
                sendLocalId = intermediateLocal.sendId,
                sendRemoteId = intermediateLocal.service.remote?.id,
                fileRemoteId = intermediateLocal.file?.id,
                uploadCompletedLocally = true,
            )
        }
        return reconciled
    }

    private suspend fun uploadPendingSendFile(
        sendApi: ServerEnvApi.Sends.Send,
        pendingUpload: PendingUploadFile,
        intermediateLocal: BitwardenSend,
        uploadFileName: String,
        itemKey: ByteArray,
    ): BitwardenSend {
        val fileRemoteId = intermediateLocal.requireRemoteFileId()
        diagnostics.sendFileUploadTargetRequested(
            sendLocalId = intermediateLocal.sendId,
            sendRemoteId = intermediateLocal.service.remote?.id,
            fileRemoteId = fileRemoteId,
        )
        val uploadTarget =
            sendApi.getFileUploadTarget(
                httpClient = httpClient,
                env = env,
                token = token,
                fileId = fileRemoteId,
            ).uploadTarget
        try {
            diagnostics.sendFileUploadStarted(
                sendLocalId = intermediateLocal.sendId,
                sendRemoteId = intermediateLocal.service.remote?.id,
                fileRemoteId = fileRemoteId,
                encryptedSize = pendingUpload.encryptedSize,
                isCreate = false,
            )
            uploadSendFile(
                httpClient = httpClient,
                env = env,
                token = token,
                target = uploadTarget,
                fileName = uploadFileName,
                filePath = pendingUpload.path,
                fileLength = pendingUpload.encryptedSize,
            )
            diagnostics.sendFileUploadCompleted(
                sendLocalId = intermediateLocal.sendId,
                sendRemoteId = intermediateLocal.service.remote?.id,
                fileRemoteId = fileRemoteId,
                isCreate = false,
            )
        } catch (e: Throwable) {
            diagnostics.sendFileUploadFailed(
                sendLocalId = intermediateLocal.sendId,
                sendRemoteId = intermediateLocal.service.remote?.id,
                fileRemoteId = fileRemoteId,
                isCreate = false,
                cleanupSucceeded = null,
                error = e,
            )
            throw e
        }
        pendingUploadCoordinator.markUploaded(pendingUpload)
        diagnostics.sendFileMarkedUploaded(
            sendLocalId = intermediateLocal.sendId,
            sendRemoteId = intermediateLocal.service.remote?.id,
            fileRemoteId = fileRemoteId,
        )
        val refreshedResponse =
            sendApi.get(
                httpClient = httpClient,
                env = env,
                token = token,
            )
        val reconciled = reconcilePendingSendUpload(
            remote =
                decodeServerSendForPush(
                    response = refreshedResponse,
                    baseLocal = intermediateLocal,
                    itemKey = itemKey,
                ),
            local = intermediateLocal,
            uploadCompletedLocally = true,
        )
        diagnostics.sendFileReconciled(
            sendLocalId = intermediateLocal.sendId,
            sendRemoteId = intermediateLocal.service.remote?.id,
            fileRemoteId = fileRemoteId,
            uploadCompletedLocally = true,
        )
        return reconciled
    }

    private suspend fun pushCreatedSend(
        update: SendUpdate.Create,
        local: BitwardenSend,
        itemKey: ByteArray,
        updatePartialRemoteLocal: (BitwardenSend?) -> Unit,
    ): BitwardenSend {
        val pendingUpload = local.file?.pendingUpload
        if (local.type == BitwardenSend.Type.File && pendingUpload != null) {
            return createFileSendAndUpload(
                update = update,
                local = local,
                itemKey = itemKey,
                pendingUpload = pendingUpload,
                updatePartialRemoteLocal = updatePartialRemoteLocal,
            )
        }

        return decodeServerSendForPush(
            response =
                sendsApi.post(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                    body = update.sendRequest,
                ),
            baseLocal = local,
            itemKey = itemKey,
        )
    }

    private suspend fun createFileSendAndUpload(
        update: SendUpdate.Create,
        local: BitwardenSend,
        itemKey: ByteArray,
        pendingUpload: PendingUploadFile,
        updatePartialRemoteLocal: (BitwardenSend?) -> Unit,
    ): BitwardenSend {
        val fileCreateResponse =
            sendsApi.postFileV2(
                httpClient = httpClient,
                env = env,
                token = token,
                body = update.sendRequest,
            )
        val createdLocal =
            decodeServerSendForPush(
                response = fileCreateResponse.requiredSendResponse,
                baseLocal = local,
                itemKey = itemKey,
            ).withPendingUpload(pendingUpload)
        updatePartialRemoteLocal(createdLocal)

        val sendRemoteId =
            requireNotNull(createdLocal.service.remote?.id) {
                "Bitwarden send id must be available after file send creation."
            }
        val fileRemoteId = createdLocal.file?.id
        val sendApi = sendsApi.focus(sendRemoteId)
        diagnostics.sendFileUploadTargetRequested(
            sendLocalId = createdLocal.sendId,
            sendRemoteId = sendRemoteId,
            fileRemoteId = fileRemoteId,
        )
        try {
            diagnostics.sendFileUploadStarted(
                sendLocalId = createdLocal.sendId,
                sendRemoteId = sendRemoteId,
                fileRemoteId = fileRemoteId,
                encryptedSize = pendingUpload.encryptedSize,
                isCreate = true,
            )
            uploadSendFile(
                httpClient = httpClient,
                env = env,
                token = token,
                target = fileCreateResponse.uploadTarget,
                fileName = fileCreateResponse.requiredSendResponse.requireFileName(),
                filePath = pendingUpload.path,
                fileLength = pendingUpload.encryptedSize,
            )
            diagnostics.sendFileUploadCompleted(
                sendLocalId = createdLocal.sendId,
                sendRemoteId = sendRemoteId,
                fileRemoteId = fileRemoteId,
                isCreate = true,
            )
        } catch (e: Throwable) {
            val cleanupSucceeded = cleanupCreatedRemoteSend(sendApi)
            diagnostics.sendFileUploadFailed(
                sendLocalId = createdLocal.sendId,
                sendRemoteId = sendRemoteId,
                fileRemoteId = fileRemoteId,
                isCreate = true,
                cleanupSucceeded = cleanupSucceeded,
                error = e,
            )
            if (cleanupSucceeded) {
                updatePartialRemoteLocal(null)
            }
            e.throwIfCancellation()
            coroutineContext.ensureActive()
            throw e
        }
        pendingUploadCoordinator.markUploaded(pendingUpload)
        diagnostics.sendFileMarkedUploaded(
            sendLocalId = createdLocal.sendId,
            sendRemoteId = sendRemoteId,
            fileRemoteId = fileRemoteId,
        )
        val refreshedResponse =
            sendApi.get(
                httpClient = httpClient,
                env = env,
                token = token,
            )
        val reconciled = reconcilePendingSendUpload(
            remote =
                decodeServerSendForPush(
                    response = refreshedResponse,
                    baseLocal = createdLocal,
                    itemKey = itemKey,
                ),
            local = createdLocal,
            uploadCompletedLocally = true,
        )
        diagnostics.sendFileReconciled(
            sendLocalId = createdLocal.sendId,
            sendRemoteId = sendRemoteId,
            fileRemoteId = fileRemoteId,
            uploadCompletedLocally = true,
        )
        return reconciled
    }

    private suspend fun cleanupCreatedRemoteSend(sendApi: ServerEnvApi.Sends.Send): Boolean {
        val cleanupResult = withContext(NonCancellable) {
            // The send was already created remotely. Always try to remove
            // that placeholder before honoring cancellation; otherwise the
            // next retry may duplicate the send instead of reusing local state.
            runCatching {
                sendApi.delete(
                    httpClient = httpClient,
                    env = env,
                    token = token,
                ).status
            }
        }
        return cleanupResult.fold(
            onSuccess = { status ->
                status == HttpStatusCode.NotFound ||
                    status.value in 200..299
            },
            onFailure = { cleanupError ->
                cleanupError.hasHttpStatusCode(HttpStatusCode.NotFound)
            },
        )
    }

    private fun sendFailurePartial(
        error: Throwable,
        local: BitwardenSend,
        partialRemoteLocal: BitwardenSend?,
    ): BitwardenSend? =
        if (error.isNonRetryableSendFileUploadError() && local.file?.pendingUpload != null) {
            (partialRemoteLocal ?: local).withoutPendingFileUpload()
        } else {
            partialRemoteLocal
        }

    private fun decodeServerSendForPush(
        response: SendEntity,
        baseLocal: BitwardenSend,
        itemKey: ByteArray,
    ): BitwardenSend {
        val (itemCrypto, globalCrypto) =
            getCodecPair(
                mode = BitwardenCrCta.Mode.DECRYPT,
                key = itemKey,
            )
        return BitwardenSend
            .encrypted(
                accountId = accountId,
                sendId = baseLocal.sendId,
                entity = response,
            ).transform(itemCrypto, globalCrypto)
    }

    private fun BitwardenSend.requireRemoteFileId(): String =
        requireNotNull(file?.id) {
            "Bitwarden send file id must be available before upload."
        }

    private fun BitwardenSend.requireFileName(): String =
        requireNotNull(file?.fileName) {
            "Bitwarden send must contain a file name for upload."
        }

    private fun SendEntity.requireFileName(): String =
        requireNotNull(file?.fileName) {
            "Bitwarden send response must contain a file name for upload."
        }

    override suspend fun deleteOnServer(
        local: BitwardenSend,
        serverId: String,
    ): RemoteWriteOutcome<BitwardenSend> {
        sendsApi.focus(serverId).delete(
            httpClient = httpClient,
            env = env,
            token = token,
        )
        return RemoteWriteOutcome.DeleteLocal
    }

    override suspend fun mergeConflict(
        local: BitwardenSend,
        server: SendEntity,
    ): RemoteWriteOutcome<BitwardenSend> {
        val decoded = decodeServerSend(
            server = server,
            sendId = local.sendId,
        )
        val reconciled = reconcilePendingSendUpload(
            remote = decoded,
            local = local,
            uploadCompletedLocally = isUploadCompletedLocally(local),
        )
        return RemoteWriteOutcome.Upsert(reconciled)
    }

    override fun mergeRemoteSuccessIntoChangedLocal(
        current: BitwardenSend,
        remoteLocal: BitwardenSend,
    ): BitwardenSend {
        val service =
            current.service.copy(
                remote = remoteLocal.service.remote,
                error = null,
                version = remoteLocal.service.version,
            )
        val currentPendingUpload = current.file?.pendingUpload
        val remoteFile = remoteLocal.file
        return if (currentPendingUpload != null && remoteFile != null) {
            current.copy(
                service = service,
                type = BitwardenSend.Type.File,
                file = remoteFile.copy(pendingUpload = currentPendingUpload),
                text = null,
            )
        } else {
            current.copy(service = service)
        }
    }

    override suspend fun markRemoteFailure(
        local: BitwardenSend,
        remoteLocal: BitwardenSend?,
        error: Throwable,
    ): BitwardenSend {
        val localWithRemote =
            when {
                error.isNonRetryableSendFileUploadError() && remoteLocal != null ->
                    remoteLocal

                remoteLocal != null ->
                    mergeRemoteSuccessIntoChangedLocal(
                        current = local,
                        remoteLocal = remoteLocal,
                    )

                error.isNonRetryableSendFileUploadError() ->
                    local.withoutPendingFileUpload()

                else ->
                    local
            }
        return super.markRemoteFailure(
            local = localWithRemote,
            remoteLocal = null,
            error = error,
        )
    }

    private suspend fun decodeServerSendOrFallback(
        server: SendEntity,
        local: BitwardenSend?,
        now: Instant,
    ): BitwardenSend =
        decodeRemoteOrFallback(
            decode = {
                val decoded = decodeServerSend(
                    server = server,
                    sendId = local?.sendId ?: cryptoGenerator.uuid(),
                )
                if (local != null) {
                    reconcilePendingSendUpload(
                        remote = decoded,
                        local = local,
                        uploadCompletedLocally = isUploadCompletedLocally(local),
                    )
                } else {
                    decoded
                }
            },
            fallback = { e ->
                recordSendDecodeFailure(server, e)
                val service = server.toDecodingFailedService(now)
                local?.copy(service = service)
                    ?: unsupportedSend(
                        now = now,
                        service = service,
                    )
            },
        )

    private fun SendEntity.toDecodingFailedService(now: Instant) =
        createDecodingFailedService(
            now = now,
            remoteId = id,
            revisionDate = revisionDate,
            deletedDate = null,
        )

    private fun unsupportedSend(
        now: Instant,
        service: BitwardenService,
    ): BitwardenSend =
        BitwardenSend(
            accountId = accountId,
            sendId = cryptoGenerator.uuid(),
            revisionDate = now,
            service = service,
            name = "⚠️ Unsupported Send",
            notes = "",
            accessCount = 0,
            accessId = "",
        )

    private fun recordSendDecodeFailure(
        server: SendEntity,
        error: Throwable,
    ) {
        val logObj = mapOf("name" to server.name?.take(2))
        val logE =
            DecodeVaultException(
                message = "Failed to decrypt a send. Structure: $logObj",
                e = error,
            )
        recordException(logE)
    }

    private fun BitwardenSend.withoutPendingFileUpload(): BitwardenSend =
        copy(
            file = file?.copy(pendingUpload = null),
        )

    private fun decodeServerSend(
        server: SendEntity,
        sendId: String,
    ): BitwardenSend {
        val (itemCrypto, globalCrypto) =
            getCodecPairFromEncrypted(
                mode = BitwardenCrCta.Mode.DECRYPT,
                keyCipherText =
                    requireNotNull(server.key) {
                        "Bitwarden send key must be present before decryption."
                    },
            )
        return BitwardenSend
            .encrypted(
                accountId = accountId,
                sendId = sendId,
                entity = server,
            ).transform(itemCrypto, globalCrypto)
    }

    private suspend fun isUploadCompletedLocally(
        local: BitwardenSend,
    ): Boolean {
        val pendingUpload = local.file?.pendingUpload
            ?: return false
        return try {
            pendingUploadCoordinator.isUploaded(pendingUpload)
        } catch (e: Throwable) {
            e.throwIfCancellation()
            false
        }
    }

    private fun reconcilePendingSendUpload(
        remote: BitwardenSend,
        local: BitwardenSend,
        uploadCompletedLocally: Boolean,
    ): BitwardenSend {
        val reconciliation =
            remote.reconcilePendingSendFileUpload(
                local = local,
                uploadCompletedLocally = uploadCompletedLocally,
            )
        return reconciliation.send
    }

    private fun reconcileCompletedPendingSendUploadOrNull(
        remote: BitwardenSend,
        local: BitwardenSend,
    ): BitwardenSend? {
        val reconciliation =
            remote.reconcilePendingSendFileUpload(
                local = local,
                uploadCompletedLocally = true,
            )
        if (reconciliation.obsoletePendingUpload == null) return null
        return reconciliation.send
    }

    private suspend fun deleteObsoletePendingUploadsAfterSave(
        previous: BitwardenSend?,
        saved: BitwardenSend,
    ) {
        val previousPendingUploads = previous?.pendingFileUploads().orEmpty()
        if (previousPendingUploads.isEmpty()) return
        val referencedPendingUploads = saved.pendingFileUploads()
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
    // Crypto helpers
    // ---------------------------------------------------------------

    private fun getCodecPair(
        mode: BitwardenCrCta.Mode,
        key: ByteArray,
    ): Pair<BitwardenCrCta, BitwardenCrCta> = buildSendCodecPair(crypto, cryptoGenerator, mode, key)

    private fun getCodecPairFromEncrypted(
        mode: BitwardenCrCta.Mode,
        keyCipherText: String,
    ): Pair<BitwardenCrCta, BitwardenCrCta> =
        buildSendCodecPairFromEncrypted(crypto, cryptoGenerator, mode, keyCipherText)
}

/**
 * Builds the (item, global) codec pair for a send from a raw key.
 *
 * The item codec uses a derived symmetric key via [makeSendCryptoKey].
 * The global codec uses the user's key for account-level fields.
 */
internal fun buildSendCodecPair(
    crypto: BitwardenCr,
    cryptoGenerator: CryptoGenerator,
    mode: BitwardenCrCta.Mode,
    key: ByteArray,
): Pair<BitwardenCrCta, BitwardenCrCta> {
    val itemCrypto =
        run {
            val symmetricCryptoKey =
                key
                    .let(cryptoGenerator::makeSendCryptoKey)
                    .let(CryptoKey.Companion::decodeSymmetricOrThrow)
            buildSyncCodec(
                crypto = crypto,
                mode = mode,
                key = BitwardenCrKey.CryptoKey(
                    symmetricCryptoKey = symmetricCryptoKey,
                ),
            )
        }
    val globalCrypto =
        buildSyncCodec(
            crypto = crypto,
            mode = mode,
            key = BitwardenCrKey.UserToken,
        )
    return itemCrypto to globalCrypto
}

/**
 * Builds the (item, global) codec pair for a send from an
 * encrypted key cipher-text. Decrypts the key with the user's
 * token first, then delegates to [buildSendCodecPair].
 */
internal fun buildSendCodecPairFromEncrypted(
    crypto: BitwardenCr,
    cryptoGenerator: CryptoGenerator,
    mode: BitwardenCrCta.Mode,
    keyCipherText: String,
): Pair<BitwardenCrCta, BitwardenCrCta> {
    val key =
        crypto
            .decoder(BitwardenCrKey.UserToken)(keyCipherText)
            .data
    return buildSendCodecPair(
        crypto = crypto,
        cryptoGenerator = cryptoGenerator,
        mode = mode,
        key = key,
    )
}
