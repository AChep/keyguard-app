package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.resolve
import com.artemchep.keyguard.platform.toKotlinxIoPath
import com.artemchep.keyguard.ui.topPresentedViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume

object DirsServiceIos : DirsService {
    private val exportDelegates = mutableSetOf<IosDocumentExportDelegate>()

    @OptIn(ExperimentalForeignApi::class)
    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect {
        val tempDir = LocalPath(NSTemporaryDirectory())
            .resolve("keyguard-${NSUUID().UUIDString}")
        val tempFile = tempDir.resolve(fileName.sanitizedExportFileName())
        try {
            SystemFileSystem.createDirectories(tempDir.toKotlinxIoPath())
            SystemFileSystem.sink(tempFile.toKotlinxIoPath())
                .buffered()
                .use { sink ->
                    write(sink)
                    sink.flush()
                }

            val url = NSURL.fileURLWithPath(tempFile.value)
            presentExportPicker(url)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(
                path = tempDir.value,
                error = null,
            )
        }
    }

    private suspend fun presentExportPicker(
        url: NSURL,
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val presenter = topPresentedViewController()
            if (presenter == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }

            val picker = UIDocumentPickerViewController(
                forExportingURLs = listOf(url),
                asCopy = true,
            )
            lateinit var delegate: IosDocumentExportDelegate
            delegate = IosDocumentExportDelegate { exportedUrl ->
                exportDelegates.remove(delegate)
                if (continuation.isActive) {
                    continuation.resume(exportedUrl)
                }
            }
            exportDelegates.add(delegate)
            picker.delegate = delegate

            continuation.invokeOnCancellation {
                exportDelegates.remove(delegate)
            }

            presenter.presentViewController(
                viewControllerToPresent = picker,
                animated = true,
                completion = null,
            )
        }
    }

    private fun String.sanitizedExportFileName(): String =
        substringAfterLast('/')
            .substringAfterLast('\\')
            .takeIf(String::isNotBlank)
            ?: "export"
}

private class IosDocumentExportDelegate(
    private val onComplete: (String?) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        onComplete(url?.absoluteString)
    }

    override fun documentPickerWasCancelled(
        controller: UIDocumentPickerViewController,
    ) {
        onComplete(null)
    }
}
