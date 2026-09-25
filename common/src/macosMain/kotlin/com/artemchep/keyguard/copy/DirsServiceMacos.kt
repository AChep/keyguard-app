package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.platform.withSecurityScopedAccess
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSSavePanel
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileManagerItemReplacementUsingNewMetadataOnly
import platform.Foundation.NSFilePosixPermissions
import platform.Foundation.NSItemReplacementDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import kotlin.coroutines.resume

/**
 * macOS counterpart of `DirsServiceIos`: the file is written to a private temporary
 * location first and then handed to the user through an `NSSavePanel`.
 *
 * The panel is what makes this work under the sandbox — the app holds no blanket grant to
 * `~/Downloads`, but the destination the user picks in the panel comes back with write
 * access attached. That is also why the bytes are produced before the panel opens: the
 * write callback can be slow (an export can be large), and running it inside the panel
 * callback would stall the main thread.
 */
object DirsServiceMacos : DirsService {
    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect {
        withPrivateExportFile(fileName, write) { file ->
            val destination = presentSavePanel(requireNotNull(file.lastPathComponent))
                ?: return@withPrivateExportFile null
            copyItem(
                source = file,
                destination = destination,
            )
            destination.absoluteString
        }
    }

    private suspend fun presentSavePanel(
        fileName: String,
    ): NSURL? = withContext(Dispatchers.Main) {
        val panel = NSSavePanel.savePanel()
        panel.nameFieldStringValue = fileName
        panel.canCreateDirectories = true
        try {
            suspendCancellableCoroutine { continuation ->
                panel.beginWithCompletionHandler { response ->
                    if (continuation.isActive) {
                        continuation.resume(panel.URL.takeIf { response == NSModalResponseOK })
                    }
                }
            }
        } finally {
            panel.close()
        }
    }

    /** Stage on the destination volume before replacing an existing document. */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    internal fun copyItem(
        source: NSURL,
        destination: NSURL,
    ) = memScoped {
        val manager = NSFileManager.defaultManager
        val error = alloc<ObjCObjectVar<NSError?>>()
        fun checkResult(success: Boolean) {
            check(success) { error.value?.localizedDescription ?: "Could not save exported file." }
        }
        destination.withSecurityScopedAccess {
            val stagingDir = checkNotNull(manager.URLForDirectory(
                directory = NSItemReplacementDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = destination,
                create = true,
                error = error.ptr,
            )) { error.value?.localizedDescription ?: "Could not create export staging directory." }
            try {
                val staged = requireNotNull(
                    stagingDir.URLByAppendingPathComponent(requireNotNull(source.lastPathComponent)),
                )
                checkResult(manager.copyItemAtURL(source, staged, error.ptr))
                checkResult(manager.setAttributes(
                    mapOf(NSFilePosixPermissions to PRIVATE_FILE_PERMISSIONS),
                    ofItemAtPath = requireNotNull(staged.path),
                    error = error.ptr,
                ))
                if (manager.fileExistsAtPath(requireNotNull(destination.path))) {
                    checkResult(manager.replaceItemAtURL(
                        originalItemURL = destination,
                        withItemAtURL = staged,
                        backupItemName = null,
                        options = NSFileManagerItemReplacementUsingNewMetadataOnly,
                        resultingItemURL = null,
                        error = error.ptr,
                    ))
                } else {
                    checkResult(manager.moveItemAtURL(staged, destination, error.ptr))
                }
            } finally {
                manager.removeItemAtURL(stagingDir, error = null)
            }
        }
    }
}
