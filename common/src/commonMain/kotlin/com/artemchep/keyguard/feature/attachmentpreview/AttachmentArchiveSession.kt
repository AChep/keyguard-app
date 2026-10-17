package com.artemchep.keyguard.feature.attachmentpreview

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.AttachmentPreviewKind
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
import com.artemchep.keyguard.common.model.attachmentPreviewKindByFileName
import com.artemchep.keyguard.common.usecase.CopyText
import com.artemchep.keyguard.crypto.PrivateTemporaryDirProvider
import com.artemchep.keyguard.crypto.createPrivateTemporaryStorage
import com.artemchep.keyguard.ui.pathBreadcrumbs
import com.artemchep.keyguard.util.io.InternalKeyguardIoApi
import com.artemchep.keyguard.util.io.scratch.PrivateTemporaryStorage
import com.artemchep.keyguard.util.io.spool.copyTo
import com.artemchep.keyguard.util.zip.ZipArchive
import com.artemchep.keyguard.util.zip.ZipArchiveEntry
import com.artemchep.keyguard.util.zip.ZipArchiveError
import com.artemchep.keyguard.util.zip.ZipArchiveException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

/** A verified export. Only explicit saving creates disk-backed storage. */
@OptIn(InternalKeyguardIoApi::class)
class AttachmentPreviewExport internal constructor(
    val name: String,
    private val storage: PrivateTemporaryStorage,
    private val finished: (AttachmentPreviewExportOutcome) -> Unit,
) {
    val id: String = Uuid.random().toString()
    private val mutex = Mutex()
    suspend fun writeTo(sink: Sink) {
        val context = currentCoroutineContext()
        withSource { it.copyTo(sink, checkCancellation = context::ensureActive) }
    }
    internal suspend fun <T> withSource(block: (Source) -> T): T = mutex.withLock {
        storage.source().buffered().use(block)
    }
    fun finish(outcome: AttachmentPreviewExportOutcome) = finished(outcome)
    suspend fun close() = mutex.withLock { storage.close() }
}

enum class AttachmentPreviewExportOutcome { Saved, Cancelled, Failed }

@OptIn(InternalKeyguardIoApi::class)
internal class AttachmentArchiveSession(
    private val scope: CoroutineScope,
    private val fileName: String,
    private val archive: ZipArchive,
    private val copyText: CopyText,
    private val onError: suspend (AttachmentPreviewError) -> Unit,
    temporaryDirProvider: PrivateTemporaryDirProvider = PrivateTemporaryDirProvider.Default,
    private val storageFactory: () -> PrivateTemporaryStorage = {
        createPrivateTemporaryStorage(temporaryDirProvider.get())
    },
    private val tree: Map<String, List<ArchiveNode>> = archiveTree(archive.entries),
) {
    private var path = ""
    private var password: String? = null
    private var job: Job? = null
    private var closed = false
    private var previewBytes: ByteArray? = null
    val state = MutableStateFlow(AttachmentPreviewState(fileName, AttachmentPreviewContent.Archive(archive)))

    private val isIdle get() = !closed && job?.isActive != true && state.value.export == null

    init {
        showFolder()
    }

    private fun showFolder() {
        if (!isIdle) return
        previewBytes?.fill(0)
        previewBytes = null
        val items = tree[path].orEmpty().map { node ->
            when (node) {
                is ArchiveNode.Folder -> AttachmentArchiveItem(
                    id = node.id,
                    name = node.name,
                    isDirectory = true,
                    size = null,
                    isEncrypted = false,
                    onOpen = { navigateFolder(node.path) },
                    onSave = null,
                )

                is ArchiveNode.File -> {
                    val entry = node.entry
                    val canSave = entry.isSupported && entry.size <= AttachmentPreviewLimits.MAX_ENTRY_EXPORT_BYTES
                    AttachmentArchiveItem(
                        id = node.id,
                        name = node.name,
                        isDirectory = false,
                        size = entry.size,
                        isEncrypted = entry.isEncrypted,
                        onOpen = if (canPreview(entry)) ({ open(entry, save = false) }) else null,
                        onSave = if (canSave) ({ open(entry, save = true) }) else null,
                    )
                }
            }
        }
        state.value = AttachmentPreviewState(
            fileName = fileName,
            content = AttachmentPreviewContent.Archive(
                source = archive,
                path = path,
                // The root alone needs no breadcrumbs.
                breadcrumbs = if (path.isEmpty()) {
                    emptyList()
                } else {
                    pathBreadcrumbs(path, rootName = fileName, onClick = ::navigateFolder)
                },
                items = items,
            ),
            onBack = if (path.isEmpty()) null else ({ navigateFolder(path.substringBeforeLast('/', "")) }),
        )
    }

    private fun navigateFolder(target: String) {
        if (!isIdle) return
        path = target
        showFolder()
    }

    @Suppress("TooGenericExceptionCaught") // UI boundary; cancellation and fatal errors are rethrown.
    private fun open(entry: ZipArchiveEntry, save: Boolean, suppliedPassword: String? = password) {
        if (!isIdle) return
        if (entry.isEncrypted && suppliedPassword == null) {
            askPassword(entry, save, incorrect = false)
            return
        }
        job = scope.launch {
            state.value = state.value.copy(
                busy = true,
                password = null,
                onCancel = { job?.cancel() },
            )
            try {
                if (save) {
                    val export = prepareExport(entry, suppliedPassword)
                    state.value = state.value.copy(export = export)
                } else {
                    val content = preview(entry, suppliedPassword)
                    previewBytes = (content as? AttachmentPreviewContent.Image)?.bytes
                    state.value = AttachmentPreviewState(
                        fileName = entry.path.substringAfterLast('/'),
                        content = content,
                        onBack = ::showFolder,
                        onSave = { open(entry, save = true) },
                    )
                }
                if (entry.isEncrypted) password = suppliedPassword
            } catch (e: Throwable) {
                e.throwIfFatalOrCancellation()
                if (e.needsArchivePasswordRetry(entry)) {
                    password = null
                    askPassword(entry, save, incorrect = true)
                } else {
                    onError(e.toArchiveOperationError(save))
                }
            } finally {
                state.value = state.value.copy(busy = false, onCancel = null)
            }
        }
    }

    private suspend fun preview(entry: ZipArchiveEntry, suppliedPassword: String?): AttachmentPreviewContent =
        withContext(Dispatchers.Default) {
            val bytes = archive.openEntry(entry.id, suppliedPassword, AttachmentPreviewLimits.MAX_ENTRY_PREVIEW_BYTES)
                .use { source ->
                    val context = currentCoroutineContext()
                    val buffer = Buffer()
                    source.copyTo(buffer, checkCancellation = context::ensureActive)
                    buffer.readByteArray()
                }
            if (attachmentPreviewKindByFileName(entry.path) == AttachmentPreviewKind.Image) {
                AttachmentPreviewContent.Image(bytes)
            } else {
                try {
                    decodeTextPreview(entry.path, bytes, copyText)
                } finally {
                    bytes.fill(0)
                }
            }
        }

    private fun askPassword(entry: ZipArchiveEntry, save: Boolean, incorrect: Boolean) {
        state.value = state.value.copy(
            password = AttachmentArchivePassword(
                incorrect = incorrect,
                onSubmit = { open(entry, save, it) },
                onCancel = { if (!closed) state.value = state.value.copy(password = null) },
            ),
        )
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup must also run on cancellation.
    private suspend fun prepareExport(entry: ZipArchiveEntry, suppliedPassword: String?): AttachmentPreviewExport {
        val storage = storageFactory()
        try {
            withContext(Dispatchers.Default) {
                archive.openEntry(
                    entry.id,
                    suppliedPassword,
                    AttachmentPreviewLimits.MAX_ENTRY_EXPORT_BYTES,
                ).use { source ->
                    val context = currentCoroutineContext()
                    storage.sink().buffered().use { sink ->
                        source.copyTo(sink, checkCancellation = context::ensureActive)
                    }
                }
                storage.sealForReading()
            }
            lateinit var export: AttachmentPreviewExport
            export = AttachmentPreviewExport(archiveExportName(entry.path), storage) { outcome ->
                scope.launch {
                    if (closed || state.value.export !== export) return@launch
                    finishExport(export, outcome)
                }
            }
            return export
        } catch (e: Throwable) {
            storage.close()
            throw e
        }
    }

    @Suppress("TooGenericExceptionCaught") // Cleanup failures must not retain the completed export.
    private suspend fun finishExport(export: AttachmentPreviewExport, outcome: AttachmentPreviewExportOutcome) {
        val error = try {
            export.close()
            AttachmentPreviewError.ArchiveExportFailed.takeIf { outcome == AttachmentPreviewExportOutcome.Failed }
        } catch (e: Throwable) {
            e.throwIfFatalOrCancellation()
            AttachmentPreviewError.ArchiveExportFailed
        }
        state.value = state.value.copy(export = null)
        error?.let { onError(it) }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            job?.cancelAndJoin()
            state.value.export?.close()
        } finally {
            job = null
            password = null
            previewBytes?.fill(0)
            previewBytes = null
            try {
                archive.close()
            } finally {
                state.value = AttachmentPreviewState(
                    fileName,
                    AttachmentPreviewContent.Error(AttachmentPreviewError.Unknown),
                )
            }
        }
    }
}

internal fun canPreview(entry: ZipArchiveEntry): Boolean = entry.isSupported && !entry.isDirectory &&
    entry.size <= AttachmentPreviewLimits.MAX_ENTRY_PREVIEW_BYTES &&
    attachmentPreviewKindByFileName(entry.path).let { it != null && it != AttachmentPreviewKind.Archive }

internal sealed interface ArchiveNode {
    val id: String
    val name: String

    data class Folder(override val name: String, val path: String) : ArchiveNode {
        override val id get() = "folder:$path"
    }

    data class File(override val name: String, val entry: ZipArchiveEntry) : ArchiveNode {
        override val id get() = "file:${entry.id}"
    }
}

internal fun archiveTree(entries: List<ZipArchiveEntry>): Map<String, List<ArchiveNode>> {
    val folders = mutableMapOf("" to mutableListOf<ArchiveNode>())
    var count = 0
    fun add(parent: String, node: ArchiveNode) {
        if (++count > AttachmentPreviewLimits.MAX_ARCHIVE_ENTRIES) {
            throw ZipArchiveException(ZipArchiveError.TooManyEntries)
        }
        folders.getValue(parent).add(node)
    }
    entries.forEach { entry ->
        if (!entry.isSafe) {
            add("", ArchiveNode.File(entry.path, entry))
            return@forEach
        }
        val parts = entry.path.removeSuffix("/").split('/')
        var parent = ""
        parts.forEachIndexed { index, name ->
            if (index < parts.lastIndex || entry.isDirectory) {
                val folder = if (parent.isEmpty()) name else "$parent/$name"
                if (folder !in folders) {
                    add(parent, ArchiveNode.Folder(name, folder))
                    folders[folder] = mutableListOf()
                }
                parent = folder
            } else add(parent, ArchiveNode.File(name, entry))
        }
    }
    return folders.mapValues { (_, children) ->
        children.sortedWith(
            compareBy<ArchiveNode> { it is ArchiveNode.File }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                .thenBy { it.id },
        )
    }
}

internal fun archiveExportName(path: String): String {
    var name = path.substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it != '\u007f' && it !in ":*?\"<>|" }.trim().trimEnd('.')
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"
    while (name.encodeToByteArray().size > MAX_EXPORT_NAME_BYTES) {
        val count = if (name.last().isLowSurrogate() && name[name.lastIndex - 1].isHighSurrogate()) 2 else 1
        name = name.dropLast(count)
    }
    return name
}

internal fun ZipArchiveError.toPreviewError(): AttachmentPreviewError = when (this) {
    ZipArchiveError.Invalid -> AttachmentPreviewError.ArchiveInvalid
    ZipArchiveError.Unsupported -> AttachmentPreviewError.ArchiveUnsupported
    ZipArchiveError.Password -> AttachmentPreviewError.ArchivePassword
    ZipArchiveError.TooLarge -> AttachmentPreviewError.TooLarge
    ZipArchiveError.TooManyEntries -> AttachmentPreviewError.ArchiveTooManyEntries
}

private const val MAX_EXPORT_NAME_BYTES = 180

private fun Throwable.toArchiveOperationError(save: Boolean): AttachmentPreviewError = when {
    save && this is ZipArchiveException && reason == ZipArchiveError.TooLarge ->
        AttachmentPreviewError.ArchiveExportTooLarge
    save && this !is ZipArchiveException -> AttachmentPreviewError.ArchiveExportFailed
    else -> toPreviewError()
}

// ZipCrypto's short password verifier can accept a wrong password, only failing at the final CRC.
private fun Throwable.needsArchivePasswordRetry(entry: ZipArchiveEntry): Boolean =
    entry.isEncrypted && this is ZipArchiveException &&
        reason in listOf(ZipArchiveError.Password, ZipArchiveError.Invalid)
