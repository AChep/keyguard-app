package com.artemchep.keyguard.feature.filepicker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.artemchep.keyguard.platform.LeUriImpl
import com.artemchep.keyguard.platform.LocalPath
import com.artemchep.keyguard.platform.toSecurityScopedBookmarkToken
import com.artemchep.keyguard.ui.CollectedEffect
import com.artemchep.keyguard.ui.topPresentedViewController
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryDestination
import com.artemchep.keyguard.util.io.atomic.AtomicDirectoryPermissions
import com.artemchep.keyguard.util.io.atomic.AtomicFilePermissions
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicPublicationPolicy
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.atomic.AtomicWriteOptions
import com.artemchep.keyguard.util.io.atomic.ExistingParentLinkPolicy
import com.artemchep.keyguard.util.io.atomic.ParentDirectoryPolicy
import com.artemchep.keyguard.util.io.atomic.SyncLevel
import com.artemchep.keyguard.util.io.atomic.SynchronizationPolicy
import com.artemchep.keyguard.util.io.atomic.writeFileAtomically
import com.artemchep.keyguard.util.io.resolve
import com.artemchep.keyguard.util.io.toNSURL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationWithSecurityScope
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.UniformTypeIdentifiers.UTTypeMovie
import platform.UniformTypeIdentifiers.UTTypeText
import platform.darwin.NSObject

@Suppress("ktlint:standard:function-naming")
@Composable
actual fun FilePickerEffect(
    flow: Flow<FilePickerIntent<*>>,
) {
    val controller = remember {
        IosFilePickerController()
    }

    CollectedEffect(flow) { intent ->
        controller.launch(intent)
    }
}

private class IosFilePickerController {
    private var delegate: IosDocumentPickerDelegate? = null
    private var intent: FilePickerIntent<*>? = null
    private var stagingDirectory: LocalPath? = null

    fun launch(intent: FilePickerIntent<*>) {
        this.intent = intent
        val picker = when (intent) {
            is FilePickerIntent.NewDocument -> createNewDocumentPicker(intent)

            is FilePickerIntent.OpenDocument -> UIDocumentPickerViewController(
                forOpeningContentTypes = intent.contentTypes(),
                asCopy = false,
            )

            is FilePickerIntent.OpenDirectory -> UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeFolder),
                asCopy = false,
            )
        }

        val delegate = IosDocumentPickerDelegate { url ->
            complete(url)
        }
        this.delegate = delegate
        picker.delegate = delegate

        val presenter = topPresentedViewController()
        if (presenter == null) {
            complete(null)
            return
        }
        presenter.presentViewController(
            viewControllerToPresent = picker,
            animated = true,
            completion = null,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun createNewDocumentPicker(
        intent: FilePickerIntent.NewDocument,
    ): UIDocumentPickerViewController {
        previousFilePickerStagingCleanup.value
        val directory = AtomicDirectoryDestination(
            root = LocalPath(NSTemporaryDirectory()),
            relativePath = AtomicRelativePath.fromComponents(
                newFilePickerStagingDirectoryName(),
            ),
        )
        stagingDirectory = directory.path
        return try {
            val file = directory.resolve(
                AtomicPathComponent.parse(
                    intent.fileName.sanitizedExportFileName(),
                ),
            )
            writeFileAtomically(
                destination = file,
                options = AtomicWriteOptions(
                    publication = AtomicPublicationPolicy.Create(
                        permissions = AtomicFilePermissions.OwnerOnly,
                    ),
                    parentDirectories = ParentDirectoryPolicy.CreateMissing(
                        permissions = AtomicDirectoryPermissions.OwnerOnly,
                    ),
                    existingParentLinks = ExistingParentLinkPolicy.Reject,
                    synchronization = SynchronizationPolicy.Required(
                        level = SyncLevel.FileSynchronized,
                    ),
                ),
            ) { sink ->
                sink.write(ByteArray(0))
            }.receipt.requireCleanupComplete()
            val url = file.path.toNSURL()
            UIDocumentPickerViewController(
                forExportingURLs = listOf(url),
                asCopy = false,
            )
        } catch (error: Throwable) {
            cleanUpStagingDirectory()
            throw error
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun complete(url: NSURL?) {
        try {
            val result = url?.toFilePickerResult(intent)
            when (val activeIntent = intent) {
                is FilePickerIntent.NewDocument -> activeIntent.onResult(result)
                is FilePickerIntent.OpenDocument -> activeIntent.onResult(result)
                is FilePickerIntent.OpenDirectory -> activeIntent.onResult(result)
                null -> Unit
            }
        } finally {
            intent = null
            delegate = null
            cleanUpStagingDirectory()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun cleanUpStagingDirectory() {
        stagingDirectory?.let { directory ->
            runCatching {
                NSFileManager.defaultManager.removeItemAtPath(
                    path = directory.value,
                    error = null,
                )
            }
        }
        stagingDirectory = null
    }
}

internal fun String.sanitizedExportFileName(): String =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .map { character ->
            when (character) {
                ':', '\u0000' -> '_'
                else -> character
            }
        }
        .joinToString(separator = "")
        .takeIf { fileName ->
            fileName.isNotBlank() &&
                fileName != "." &&
                fileName != ".."
        }
        ?: "export"

private val previousFilePickerStagingCleanup = lazy {
    cleanUpFilePickerStagingDirectories(
        root = LocalPath(NSTemporaryDirectory()),
    )
}

private class IosDocumentPickerDelegate(
    private val onComplete: (NSURL?) -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onComplete(didPickDocumentsAtURLs.firstOrNull() as? NSURL)
    }

    override fun documentPickerWasCancelled(
        controller: UIDocumentPickerViewController,
    ) {
        onComplete(null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.toFilePickerResult(
    intent: FilePickerIntent<*>?,
): FilePickerResult = withSecurityScopedAccess {
    val uri = absoluteString.orEmpty()
    FilePickerResult(
        uri = LeUriImpl(uri),
        name = lastPathComponent,
        size = path?.let(::fileSizeOrNull),
        accessToken = if (intent.shouldCreateSecurityScopedToken()) {
            createSecurityScopedAccessToken()
        } else {
            null
        },
    )
}

private fun FilePickerIntent.OpenDocument.contentTypes(): List<UTType> {
    val types = mimeTypes
        .filterNot { mimeType -> mimeType == FilePickerIntent.MIME_TYPE_ALL }
        .flatMap { mimeType -> contentTypesByMimeType(mimeType) }
    return types.ifEmpty {
        listOf(UTTypeData)
    }
}

private fun contentTypesByMimeType(
    mimeType: String,
): List<UTType> = when (mimeType) {
    "audio/*" -> listOf(UTTypeAudio)

    "image/*" -> listOf(UTTypeImage)

    "text/*" -> listOf(UTTypeText)

    "video/*" -> listOf(UTTypeMovie)

    "application/x-kdbx",
    "application/x-keepass",
    -> listOfNotNull(
        UTType.typeWithMIMEType(mimeType),
        UTType.typeWithFilenameExtension("kdbx"),
        UTType.typeWithFilenameExtension("key"),
    )

    else -> listOfNotNull(
        UTType.typeWithMIMEType(mimeType),
    )
}

private fun FilePickerIntent<*>?.shouldCreateSecurityScopedToken(): Boolean = when (this) {
    is FilePickerIntent.NewDocument -> persistableUriPermission
    is FilePickerIntent.OpenDocument -> persistableUriPermission
    is FilePickerIntent.OpenDirectory -> persistableUriPermission
    null -> false
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> NSURL.withSecurityScopedAccess(
    block: () -> T,
): T {
    val didStartAccessing = startAccessingSecurityScopedResource()
    return try {
        block()
    } finally {
        if (didStartAccessing) {
            stopAccessingSecurityScopedResource()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.createSecurityScopedAccessToken(): String? = runCatching {
    val data = bookmarkDataWithOptions(
        options = NSURLBookmarkCreationWithSecurityScope,
        includingResourceValuesForKeys = null,
        relativeToURL = null,
        error = null,
    )
    data?.toSecurityScopedBookmarkToken()
}.getOrNull()

@OptIn(ExperimentalForeignApi::class)
private fun fileSizeOrNull(
    path: String,
): Long? = runCatching {
    val attributes = NSFileManager.defaultManager
        .attributesOfItemAtPath(path, error = null)
    (attributes?.get(NSFileSize) as? NSNumber)?.longLongValue
}.getOrNull()
