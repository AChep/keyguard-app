package com.artemchep.keyguard.common.service.download.store

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.service.download.DownloadInfoEntity
import com.artemchep.keyguard.common.service.download.DownloadWriter
import com.artemchep.keyguard.common.service.download.writeVerifiedSource
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writePrivatelyAtomically
import com.artemchep.keyguard.util.io.source
import com.artemchep.keyguard.util.io.toFileUriString
import com.artemchep.keyguard.util.io.toKotlinxIoPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Keeps downloads isolated by ID while exposing the attachment's file type. */
open class NamedDownloadFileStore(
    private val directory: suspend () -> AtomicDirectoryDestination,
) : DownloadFileStoreLocalPath() {
    private val mutex = Mutex()

    override suspend fun destination(info: DownloadInfoEntity): AtomicFileDestination =
        directory().cacheFile(info)

    override suspend fun completedUri(info: DownloadInfoEntity, writerUri: String?): String = uri(info)

    override suspend fun uri(info: DownloadInfoEntity): String = mutex.withLock {
        val root = directory()
        val handoffDirectory = root.handoffDirectory(info)
        val target = handoffDirectory.resolve(AtomicPathComponent.parse(downloadDisplayFileName(info.name)))
        val cache = root.cacheFile(info).path
        // Keep the canonical writer path independent of mutable attachment names.
        // Refresh the handoff atomically so callers never see a partial copy.
        if (SystemFileSystem.exists(cache.toKotlinxIoPath())) {
            cache.source().buffered().use { source ->
                writePrivatelyAtomically(
                    destination = target,
                    parentDirectories = ParentDirectoryPolicy.CreateMissing(AtomicDirectoryPermissions.OwnerOnly),
                    synchronization = SynchronizationPolicy.Required(SyncLevel.FileSynchronized),
                ) { sink ->
                    DownloadWriter.SinkWriter(sink).writeVerifiedSource(source)
                }.receipt.requireCleanupComplete()
            }
            deleteEntries(handoffDirectory.path.toKotlinxIoPath(), keep = target.path.toKotlinxIoPath())
        }
        target.path.toFileUriString()
    }

    override suspend fun delete(info: DownloadInfoEntity): Boolean = mutex.withLock {
        val deleted = super.delete(info)
        val handoffsDeleted = runCatchingNonFatal {
            val path = directory().handoffDirectory(info).path.toKotlinxIoPath()
            if (SystemFileSystem.exists(path)) {
                deleteEntries(path)
                SystemFileSystem.delete(path, mustExist = false)
            }
        }.isSuccess
        deleted && handoffsDeleted
    }
}

private fun AtomicDirectoryDestination.cacheFile(info: DownloadInfoEntity) =
    resolve(AtomicPathComponent.parse("${info.id}.bin"))

private fun AtomicDirectoryDestination.handoffDirectory(info: DownloadInfoEntity) =
    resolveDirectory(AtomicPathComponent.parse(info.id))

private fun deleteEntries(directory: Path, keep: Path? = null) {
    SystemFileSystem.list(directory)
        .filter { it != keep }
        .forEach { SystemFileSystem.delete(it, mustExist = false) }
}

internal fun downloadDisplayFileName(name: String): String {
    val sanitized = buildString {
        name.forEach { character ->
            append(if (character.isISOControl() || character in "/\\:") '_' else character)
        }
    }.trim().trim('.')
        .ifEmpty { "attachment" }
    val extension = sanitized.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= 32 }
        ?.let { ".$it" }.orEmpty()
    var stem = sanitized.removeSuffix(extension)
    // Leave room below common 255-byte component limits, preserving the suffix.
    while ((stem + extension).encodeToByteArray().size > MAX_FILE_NAME_BYTES) stem = stem.dropLast(1)
    return stem + extension
}

private const val MAX_FILE_NAME_BYTES = 200
