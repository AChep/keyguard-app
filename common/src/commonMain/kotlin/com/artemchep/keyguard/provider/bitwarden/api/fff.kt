package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.Clock
import kotlin.Any
import kotlin.Boolean
import kotlin.String
import kotlin.Throwable
import kotlin.Unit
import kotlin.let

suspend fun merge(
    remote: BitwardenCipher,
    local: BitwardenCipher?,
    getPasswordStrength: GetPasswordStrength,
): BitwardenCipher {
    val attachments = remote.attachments.toMutableList()
    local?.attachments?.forEachIndexed { localIndex, attachment ->
        val localAttachment = attachment as? BitwardenCipher.Attachment.Local
            ?: return@forEachIndexed

        // Skip collisions.
        val remoteIndex = attachments
            .indexOfFirst { it.id == localAttachment.id }
        if (remoteIndex >= 0) {
            // This attachment already exists on remote, so
            // we just keep it as is.
            return@forEachIndexed
        }

        val parent = local.attachments
            .getOrNull(localIndex - 1)
        val parentIndex = attachments
            .indexOfFirst { it.id == parent?.id }
        if (parentIndex >= 0) {
            attachments.add(parentIndex + 1, localAttachment)
        } else {
            if (parent != null) {
                attachments.add(localAttachment)
            } else {
                attachments.add(0, localAttachment)
            }
        }
    }

    var login = remote.login
    // Calculate or copy over the password strength of
    // the password.
    if (remote.login != null) run {
        val password = remote.login.password
            ?: return@run
        val strength = local?.login?.passwordStrength
            .takeIf { local?.login?.password == remote.login.password }
        // Generate a password strength badge.
            ?: getPasswordStrength(password)
                .attempt()
                .bind()
                .getOrNull()
                ?.let { ps ->
                    BitwardenCipher.Login.PasswordStrength(
                        password = password,
                        crackTimeSeconds = ps.crackTimeSeconds,
                        version = ps.version,
                    )
                }
        login = login?.copy(
            passwordStrength = strength,
        )
    }

    val ignoredAlerts = local?.ignoredAlerts.orEmpty()
    return remote.copy(
        login = login,
        attachments = attachments,
        ignoredAlerts = ignoredAlerts,
    )
}

interface RemotePutScope<Remote> {
    fun updateRemoteModel(remote: Remote)
}

suspend fun <
        Local : BitwardenService.Has<Local>,
        LocalDecoded : Any,
        Remote : Any,
        RemoteDecoded : Any,
        > syncX(
    sync: (String, IO<Any?>) -> IO<Any?> = { _, io -> io },
    name: String,
    localItems: Collection<Local>,
    localLens: SyncManager.LensLocal<Local>,
    localReEncoder: (Local) -> RemoteDecoded,
    localDecoder: (Local, Remote?) -> LocalDecoded,
    localDecodedToString: (LocalDecoded) -> String = { it.toString() },
    localDeleteById: suspend (List<String>) -> Unit,
    localPut: suspend (List<RemoteDecoded>) -> Unit,
    shouldOverwrite: (Local, Remote) -> Boolean = { _, _ -> false },
    remoteItems: Collection<Remote>,
    remoteLens: SyncManager.Lens<Remote>,
    remoteDecoder: suspend (Remote, Local?) -> RemoteDecoded,
    remoteDecodedToString: (RemoteDecoded) -> String = { it.toString() },
    remoteDecodedFallback: suspend (Remote, Local?, Throwable) -> RemoteDecoded,
    remoteDeleteById: suspend (String) -> Unit,
    remotePut: suspend RemotePutScope<Remote>.(LocalDecoded) -> RemoteDecoded,
    onLog: (String, LogLevel) -> Unit,
) {
    onLog(
        "[Start] Starting to sync the $name: " +
                "${localItems.size} local items, " +
                "${remoteItems.size} remote items.",
        LogLevel.INFO,
    )

    val df = SyncManager(
        local = localLens,
        remote = remoteLens,
    ).df(
        localItems = localItems,
        remoteItems = remoteItems,
        shouldOverwrite = shouldOverwrite,
    )

    //
    // Write changes to local storage as these
    // are quite fast to do.
    //

    localDeleteById(
        df.localDeletedCipherIds
            .map { localLens.getLocalId(it.local) },
    )

    val localPutCipherDecoded = df.localPutCipher
        .map { (localOrNull, remote) ->
            ioEffect { remoteDecoder(remote, localOrNull) }
                .handleError { e ->
                    val remoteId = remoteLens.getId(remote)
                    val localId = localOrNull?.let(localLens.getLocalId)
                    val msg = "[local] Failed to decode the item: " +
                            "remote_id=$remoteId, " +
                            "local_id=$localId"
                    onLog(msg, LogLevel.WARNING)
                    e.printStackTrace()

                    remoteDecodedFallback(remote, localOrNull, e)
                }
        }
        .parallel(Dispatchers.Default)
        .measure { duration, v ->
            val msg = "[local] Decoding $name took $duration; " +
                    "${v.size} entries decoded."
            onLog(msg, LogLevel.INFO)
        }
        .bind()
    localPut(localPutCipherDecoded)

    //
    // Write changes to remote server.
    //

    suspend fun handleFailedToPut(
        local: Local,
        remote: Remote? = null,
        e: Throwable,
    ) {
        e.printStackTrace()
        onLog("Failed to put!! $e", LogLevel.INFO)

        val code = when (e) {
            is ApiException -> e.code.value
            else -> BitwardenService.Error.CODE_UNKNOWN
        }
        val newError = BitwardenService.Error(
            code = code,
            message = e.localizedMessage
                ?: e.message,
            revisionDate = Clock.System.now(),
        )
        val newRemote = remote
            ?.let {
                BitwardenService.Remote(
                    id = remoteLens.getId(it),
                    revisionDate = remoteLens.getRevisionDate(it),
                    deletedDate = remoteLens.getDeletedDate(it),
                )
            }
            ?: local.service.remote
        val newService = local.service.copy(
            error = newError,
            remote = newRemote,
        )
        val newLocal = local
            .withService(newService)

        val update = localReEncoder(newLocal)
        localPut(listOf(update))
    }

    df.remoteDeletedCipherIds
        .map { entry ->
            val localId = localLens.getLocalId(entry.local)
            val remoteId = remoteLens.getId(entry.remote)
            ioEffect { remoteDeleteById(remoteId) }
                .handleErrorTap { e ->
                    handleFailedToPut(entry.local, e = e)
                }
                .effectMap {
                    val update = listOf(localId)
                    localDeleteById(update)
                }
                // If a client wants to block an access to database
                // while we are doing this operation -> this is a right place.
                .let { io -> sync(localId, io) }
                .attempt()
        }
        .parallel(Dispatchers.Default)
        .bind()

    df.remotePutCipher
        .map { entry ->
            val localId = localLens.getLocalId(entry.local)
            ioEffect { localDecoder(entry.local, entry.remote) }
                .handleErrorTap { e ->
                    handleFailedToPut(entry.local, e = e)
                }
                .flatMap { localDecoded ->
                    var lastRemote: Remote? = null
                    val scope = object : RemotePutScope<Remote> {
                        override fun updateRemoteModel(remote: Remote) {
                            lastRemote = remote
                        }
                    }
                    ioEffect { remotePut(scope, localDecoded) }
                        .handleErrorTap { e ->
                            handleFailedToPut(
                                local = entry.local,
                                remote = lastRemote,
                                e = e,
                            )
                        }
                        .effectMap {
                            val update = listOf(it)
                            localPut(update)
                        }
                        // If a client wants to block an access to database
                        // while we are doing this operation -> this is a right place.
                        .let { io -> sync(localId, io) }
                }
                // I want to filter out the items that were failed to
                // be decoded.
                .attempt()
        }
        .parallel(Dispatchers.Default)
        .measure { duration, v ->
            val msg = "[remote] Decoding $name took $duration; " +
                    "${v.size} entries decoded."
            onLog(msg, LogLevel.INFO)
        }
        .bind()
}
