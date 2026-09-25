package com.artemchep.keyguard.common.service.backup

import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.platform.appleBookmarkCreationOptions
import com.artemchep.keyguard.platform.resolveSecurityScopedUrlOrNull
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingImmediatelyAvailableMetadataOnly
import platform.Foundation.NSFileCoordinatorReadingWithoutChanges
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileNoSuchFileError
import platform.Foundation.NSFileReadNoPermissionError
import platform.Foundation.NSFileReadNoSuchFileError
import platform.Foundation.NSFileWriteNoPermissionError
import platform.Foundation.NSURL
import kotlin.coroutines.CoroutineContext

/** Owns the directory grant, never exposing bookmark bytes in an error. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class AppleBackupFolderAccess private constructor(
    private val url: NSURL,
    private val accessing: Boolean,
    val refreshedConfig: BackupStoreConfig.Local?,
) : AutoCloseable {
    private var closed = false

    suspend fun <T> coordinate(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey? = null,
        block: Coordination.(String) -> T,
    ): T {
        check(!closed) { "Backup folder is closed." }
        val mutation = operation == BackupObjectStoreOperation.Write || operation == BackupObjectStoreOperation.Delete
        require(!mutation || key != null) { "Object mutations require a backup key." }
        // Only a mutation also claims the object URL.
        val objectKey = key.takeIf { mutation }
        return suspendCancellableCoroutine { continuation ->
            val coordinator = NSFileCoordinator(filePresenter = null)
            continuation.invokeOnCancellation { coordinator.cancel() }
            continuation.resumeWith(runCatching {
                val coordination = Coordination(coordinator, continuation.context)
                coordination.coordinateUrl(url, operation, objectKey) { path -> coordination.block(path) }
            })
        }
    }

    /** One synchronous operation, including nested reads, shares a cancellation hook. */
    class Coordination internal constructor(
        private val coordinator: NSFileCoordinator,
        private val context: CoroutineContext,
    ) {
        /** Materialize a provider placeholder after the caller validates its parent handles. */
        fun <T> coordinateObjectRead(root: String, key: BackupObjectKey, block: () -> T): T {
            val objectUrl = NSURL.fileURLWithPath("$root/${key.value}")
            return coordinateUrl(objectUrl, BackupObjectStoreOperation.Read) { coordinatedPath ->
                // A renamed object no longer denotes this key.
                if (coordinatedPath != objectUrl.path) {
                    throw BackupObjectStoreException.Transient(BackupObjectStoreOperation.Read, key)
                }
                block()
            }
        }

        internal fun <T> coordinateUrl(
            url: NSURL,
            operation: BackupObjectStoreOperation,
            key: BackupObjectKey? = null,
            block: (String) -> T,
        ): T = memScoped {
            context.ensureActive()
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            var result: Result<T>? = null
            fun access(coordinatedUrl: NSURL?, objectUrl: NSURL? = null) {
                result = runCatching {
                    context.ensureActive()
                    val path = coordinatedUrl?.path
                        ?: throw BackupObjectStoreException.PermissionDenied(operation, key)
                    if (key != null && objectUrl?.path != NSURL.fileURLWithPath("$path/${key.value}").path) {
                        throw BackupObjectStoreException.Transient(operation, key)
                    }
                    block(path)
                }
            }
            if (key != null) {
                // A directory claim alone does not coordinate access to its children.
                // Acquire both URLs together so Foundation orders the claims safely.
                val objectUrl = NSURL.fileURLWithPath("${requireNotNull(url.path)}/${key.value}")
                val writingOptions = if (operation == BackupObjectStoreOperation.Delete) {
                    NSFileCoordinatorWritingForDeleting
                } else {
                    0uL
                }
                coordinator.coordinateReadingItemAtURL(
                    url,
                    NSFileCoordinatorReadingWithoutChanges or
                        NSFileCoordinatorReadingImmediatelyAvailableMetadataOnly,
                    objectUrl,
                    writingOptions,
                    error = error.ptr,
                    byAccessor = { root, item -> access(root, item) },
                )
            } else {
                coordinator.coordinateReadingItemAtURL(
                    url,
                    options = 0u,
                    error = error.ptr,
                    byAccessor = { access(it) },
                )
            }
            val completed = result ?: run {
                // Preserve coroutine cancellation instead of reporting a provider failure.
                context.ensureActive()
                throw when (error.value?.code) {
                    NSFileReadNoPermissionError, NSFileWriteNoPermissionError,
                    NSFileNoSuchFileError, NSFileReadNoSuchFileError,
                    -> BackupObjectStoreException.PermissionDenied(operation, key)
                    else -> BackupObjectStoreException.Transient(operation, key)
                }
            }
            completed.getOrThrow()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (accessing) url.stopAccessingSecurityScopedResource()
    }

    companion object {
        fun open(config: BackupStoreConfig.Local): AppleBackupFolderAccess = memScoped {
            val path = requireNotNull(config.path) { "Select a backup folder." }
            val stale = alloc<BooleanVar>()
            stale.value = false
            val token = config.accessToken
            val url = when {
                token != null -> token.resolveSecurityScopedUrlOrNull(stale.ptr)
                path.startsWith("/") -> NSURL.fileURLWithPath(path)
                else -> NSURL.URLWithString(path)?.takeIf { it.isFileURL() }
            } ?: throw BackupObjectStoreException.PermissionDenied(BackupObjectStoreOperation.Open)
            val accessing = url.startAccessingSecurityScopedResource()
            try {
                // A false return also occurs for locations already accessible inside the sandbox.
                // The coordinated I/O below remains authoritative about actual permissions.
                val refreshed = if (token != null && (stale.value || url.absoluteString != path)) {
                    url.bookmarkDataWithOptions(appleBookmarkCreationOptions, null, null, null)
                        ?.toSecurityScopedBookmarkToken()
                        ?.let { config.copy(path = url.absoluteString, accessToken = FileAccessToken(it)) }
                } else {
                    null
                }
                AppleBackupFolderAccess(url, accessing, refreshed)
            } catch (e: Throwable) {
                if (accessing) url.stopAccessingSecurityScopedResource()
                throw e
            }
        }
    }
}
