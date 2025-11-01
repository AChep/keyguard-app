package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.common.exception.ApiException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.handleErrorWith
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.ioRaise
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.SyncScope
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenProfile
import com.artemchep.keyguard.core.store.bitwarden.BitwardenService
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager
import com.artemchep.keyguard.provider.bitwarden.sync.SyncManager.Companion.roundToMillis
import kotlinx.coroutines.Dispatchers
import kotlin.time.Clock
import kotlin.Any
import kotlin.Boolean
import kotlin.String
import kotlin.Throwable
import kotlin.Unit
import kotlin.let
import kotlin.time.Instant

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

suspend fun merge(
    remote: BitwardenProfile,
    local: BitwardenProfile?,
): BitwardenProfile {
    val hidden = local?.hidden == true
    return remote.copy(
        hidden = hidden,
    )
}

interface RemotePutScope<Remote> {
    val force: Boolean

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
    getDateMillis: (Instant) -> Long = ::roundToMillis,
    localItems: Collection<Local>,
    localLens: SyncManager.LensLocal<Local>,
    localReEncoder: (Local) -> RemoteDecoded,
    localDecoder: (Local, Remote?) -> LocalDecoded,
    localDecodedToString: (LocalDecoded) -> String = { it.toString() },
    localDeleteById: suspend (List<String>) -> Unit,
    localPut: suspend (List<RemoteDecoded>) -> Unit,
    merge: suspend (Local, RemoteDecoded) -> Local? = { _, _ -> null },
    shouldOverwriteLocal: (Local, Remote) -> Boolean = { _, _ -> false },
    shouldOverwriteRemote: (Local, Remote) -> Boolean = { _, _ -> false },
    remoteItems: Collection<Remote>,
    remoteLens: SyncManager.Lens<Remote>,
    remoteDecoder: suspend (Remote, Local?) -> RemoteDecoded,
    remoteDecodedToString: (RemoteDecoded) -> String = { it.toString() },
    remoteDecodedFallback: suspend (Remote, Local?, Throwable) -> RemoteDecoded,
    remoteDeleteById: suspend (String) -> Unit,
    remotePut: suspend RemotePutScope<Remote>.(LocalDecoded) -> RemoteDecoded,
    onLog: suspend (String, LogLevel) -> Unit,
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
        getDateMillis = getDateMillis,
    ).df(
        localItems = localItems,
        remoteItems = remoteItems,
        shouldOverwriteLocal = shouldOverwriteLocal,
        shouldOverwriteRemote = shouldOverwriteRemote,
    )
    onLog(
        "[Start] Starting to sync the $name: " +
                "${localItems.size} local items, " +
                "${remoteItems.size} remote items.",
        LogLevel.INFO,
    )

    //
    // Write changes to local storage as these
    // are quite fast to do.
    //

    val localDeletedCipherIds = df.localDeletedCipherIds
        .map { localLens.getLocalId(it.local) }
    onLog(
        "[local] Deleting ${localDeletedCipherIds.size} $name entries...",
        LogLevel.DEBUG,
    )
    localDeleteById(localDeletedCipherIds)

    /**
     * Decodes the remote and saves it as
     * a local item, replacing previously
     * existing one if needed.
     */
    fun getLocalPutRemoteDecodedModelIo(
        localOrNull: Local? = null,
        remote: Remote,
    ) = ioEffect {
        val remoteId = remote.let(remoteLens.getId)
        onLog(
            "[local] Decoding $remoteId $name entry...",
            LogLevel.DEBUG,
        )
        remoteDecoder(remote, localOrNull)
    }

    fun IO<RemoteDecoded>.handleErrorWithRemoteDecodedFallback(
        localOrNull: Local? = null,
        remote: Remote,
    ) = this
        // If we fail to decode the remote, then we generate a placeholder
        // entity instead to show a user that something has gone wrong.
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

    val localPutCipherDecodedIos = df.localPutCipher
        .map { (localOrNull, remote) ->
            getLocalPutRemoteDecodedModelIo(localOrNull, remote)
                .handleErrorWithRemoteDecodedFallback(localOrNull, remote)
        }
    // Save the decoded items in groups. This way if a sync takes
    // a lot of time we at least save some intermediate progress and
    // later start from it.
    localPutCipherDecodedIos
        .windowed(
            size = 1000,
            step = 1000,
            partialWindows = true,
        )
        .forEach { ios ->
            val localPutCipherDecoded = ios
                .parallel(Dispatchers.Default)
                .measure { duration, v ->
                    val msg = "[local] Decoding $name took $duration; " +
                            "${v.size} entries decoded."
                    onLog(msg, LogLevel.INFO)
                }
                .bind()
            localPut(localPutCipherDecoded)
        }

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

    /**
     * Decodes the remote and saves it as
     * a local item, replacing previously
     * existing one if needed.
     */
    fun getRemotePutIo(
        local: Local,
        remoteOrNull: Remote?,
        force: Boolean,
    ) = ioEffect { localDecoder(local, remoteOrNull) }
        .handleErrorTap { e ->
            handleFailedToPut(local, e = e)
        }
        .flatMap { localDecoded ->
            var lastRemote: Remote? = null
            val scope = object : RemotePutScope<Remote> {
                override val force: Boolean get() = force

                override fun updateRemoteModel(remote: Remote) {
                    lastRemote = remote
                }
            }
            ioEffect {
                val newRemote = remotePut(scope, localDecoded)
                val msg = run {
                    val params = listOf(
                        "local_id" to localLens.getLocalId(local),
                        "local_local_rev_date" to localLens.getLocalRevisionDate(local),
                        // Last known remote revision date of the locally
                        // available service.
                        "local_remote_rev_date" to local.service.remote?.revisionDate,
                    ).joinToString { (key, value) ->
                        "$key=$value"
                    }
                    "[remote] Put successful... $params"
                }
                onLog(msg, LogLevel.DEBUG)
                newRemote
            }
                .handleErrorTap { e ->
                    handleFailedToPut(
                        local = local,
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
                .let { io -> sync(localLens.getLocalId(local), io) }
        }

    df.mergeCipher
        .map { entry ->
            val local = entry.local
            val remote = entry.remote
            ioEffect {
                fun saveRemote(remoteDecodedIo: IO<RemoteDecoded>): IO<Any?> = remoteDecodedIo
                    .handleErrorWithRemoteDecodedFallback(local, remote)
                    // 1. Save the remote item to the local storage.
                    .effectMap {
                        val list = listOf(it)
                        localPut(list)

                        it
                    }

                fun saveMerged(mergedIo: IO<Local>): IO<Any?> = mergedIo
                    // 1. Save the merged item to the local storage. This is the
                    // same as if we have updated the new remote item.
                    .effectMap {
                        val mergedReEncoded = localReEncoder(it)
                        val mergedReEncodedAsList = listOf(mergedReEncoded)
                        localPut(mergedReEncodedAsList)

                        it
                    }
                    // 2. Save the merged item to the remote storage.
                    .effectMap {
                        getRemotePutIo(
                            local = it,
                            remoteOrNull = remote,
                            force = false,
                        )
                            .attempt()
                            .bind()
                    }

                val msg = run {
                    val params = listOf(
                        "remote_rev_date" to remoteLens.getRevisionDate(remote),
                        "local_local_rev_date" to localLens.getLocalRevisionDate(local),
                        // Last known remote revision date of the locally
                        // available service.
                        "local_remote_rev_date" to local.service.remote?.revisionDate,
                    ).joinToString { (key, value) ->
                        "$key=$value"
                    }
                    "[local] Merging ${remoteLens.getId(remote)} $name entry " +
                            "with ${localLens.getLocalId(local)}... $params"
                }
                onLog(msg, LogLevel.DEBUG)

                val remoteDecodedResult = getLocalPutRemoteDecodedModelIo(local, remote)
                    .attempt()
                    .bind()
                remoteDecodedResult.fold(
                    ifLeft = { e ->
                        val remoteDecodedIo = ioRaise<RemoteDecoded>(e)
                        saveRemote(remoteDecodedIo)
                    },
                    ifRight = { remoteDecoded ->
                        val merged = merge(local, remoteDecoded)
                        if (merged != null) {
                            val mergedIo = io(merged)
                            return@fold saveMerged(mergedIo)
                        }

                        val remoteDecodedIo = io(remoteDecoded)
                        saveRemote(remoteDecodedIo)
                    },
                ).bind()
            }
        }
        .parallel(Dispatchers.Default)
        .bind()

    df.remoteDeletedCipherIds
        .map { entry ->
            val localId = localLens.getLocalId(entry.local)
            val remoteId = remoteLens.getId(entry.remote)
            ioEffect {
                onLog(
                    "[local] Decoding $remoteId $name entry...",
                    LogLevel.DEBUG,
                )
                remoteDeleteById(remoteId)
            }
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
            getRemotePutIo(
                local = entry.local,
                remoteOrNull = entry.remote,
                force = entry.force,
            )
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
