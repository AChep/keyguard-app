package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.dirs.DirsService
import com.artemchep.keyguard.ui.topPresentedViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Sink
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object DirsServiceIos : DirsService {
    override fun saveToDownloads(
        fileName: String,
        write: suspend (Sink) -> Unit,
    ): IO<String?> = ioEffect {
        withPrivateExportFile(fileName, write) { file ->
            presentExportPicker(file)
        }
    }

    private suspend fun presentExportPicker(
        url: NSURL,
    ): String? = withContext(Dispatchers.Main) {
        val presenter = checkNotNull(topPresentedViewController()) {
            "No window is available to save the exported file."
        }
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(url),
            asCopy = true,
        )
        // UIDocumentPicker's delegate is weak. Retain it until dismissal finishes.
        var delegate: IosDocumentExportDelegate? = null
        try {
            suspendCancellableCoroutine { continuation ->
                delegate = IosDocumentExportDelegate { result ->
                    if (continuation.isActive) continuation.resumeWith(result)
                }
                picker.delegate = delegate
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        } finally {
            withContext(NonCancellable) {
                if (picker.presentingViewController != null) {
                    suspendCoroutine { continuation ->
                        picker.dismissViewControllerAnimated(false) { continuation.resume(Unit) }
                    }
                }
                if (picker.delegate === delegate) picker.delegate = null
                delegate = null
            }
        }
    }
}

private class IosDocumentExportDelegate(
    private val onComplete: (Result<String?>) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        val result = runCatching {
            checkNotNull(url?.absoluteString) {
                "The document picker did not return a saved file."
            }
        }
        onComplete(result)
    }

    override fun documentPickerWasCancelled(
        controller: UIDocumentPickerViewController,
    ) {
        onComplete(Result.success(null))
    }
}
