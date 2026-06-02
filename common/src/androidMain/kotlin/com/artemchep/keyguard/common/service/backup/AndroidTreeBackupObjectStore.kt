package com.artemchep.keyguard.common.service.backup

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.artemchep.keyguard.common.io.useBufferedSink
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.kodein.di.DirectDI
import org.kodein.di.instance

class AndroidTreeBackupObjectStore internal constructor(
    private val documentClient: AndroidTreeDocumentClient,
) : BackupObjectStore {
    constructor(
        contentResolver: ContentResolver,
        treeUri: Uri,
    ) : this(
        documentClient = AndroidTreeDocumentsContractClient(
            contentResolver = contentResolver,
            treeUri = treeUri,
        ),
    )

    override val capabilities: BackupObjectStoreCapabilities = BackupObjectStoreCapabilities(
        atomicWholeObjectWrite = false,
        atomicReplace = false,
        rangeRead = false,
        strongReadAfterWrite = true,
        strongListAfterWrite = true,
    )

    override suspend fun stat(
        key: BackupObjectKey,
    ): BackupObjectInfo? = translate(
        operation = BackupObjectStoreOperation.Stat,
        key = key,
    ) {
        val document = resolveDocument(key) ?: return@translate null
        document
            .takeUnless { it.isDirectory }
            ?.toObjectInfo(key)
    }

    override suspend fun read(
        key: BackupObjectKey,
        range: BackupByteRange?,
    ): Source = translate(
        operation = BackupObjectStoreOperation.Read,
        key = key,
    ) {
        if (range != null) {
            throw BackupObjectStoreException.InvalidRange(
                key = key,
                range = range,
            )
        }
        val document = resolveDocument(key)
            ?.takeUnless { it.isDirectory }
            ?: throw BackupObjectStoreException.NotFound(
                key = key,
                operation = BackupObjectStoreOperation.Read,
            )
        val stream = documentClient.openInputStream(document)
            ?: throw providerUnavailable(
                operation = BackupObjectStoreOperation.Read,
                key = key,
                message = "Document provider returned null input stream.",
            )
        stream.toBackupReadSource(key)
    }

    override suspend fun write(
        key: BackupObjectKey,
        mode: BackupWriteMode,
        write: suspend (kotlinx.io.Sink) -> Unit,
    ): BackupObjectInfo = translate(
        operation = BackupObjectStoreOperation.Write,
        key = key,
    ) {
        val parts = key.parts()
        val parent = ensureDirectory(
            parts = parts.dropLast(1),
            key = key,
        )
        val fileName = parts.last()
        val existing = findChild(parent, fileName)
        if (mode == BackupWriteMode.Create && existing != null) {
            throw BackupObjectStoreException.AlreadyExists(key)
        }
        if (existing?.isDirectory == true) {
            throw BackupObjectStoreException.AlreadyExists(key)
        }

        val tempName = "$fileName.${System.nanoTime()}.tmp"
        val tempDocument = createFile(
            parent = parent,
            name = tempName,
            key = key,
        )
        var stagedExisting: AndroidTreeDocumentEntry? = null
        var tempPublished = false
        try {
            writeDocument(
                document = tempDocument,
                key = key,
                write = write,
            )
            stagedExisting = existing
                ?.let { existingDocument ->
                    stageExistingDocument(
                        document = existingDocument,
                        fileName = fileName,
                        key = key,
                    )
                }
            val info = try {
                val publishedDocument = publishTempFile(
                    tempDocument = tempDocument,
                    fileName = fileName,
                    key = key,
                )
                tempPublished = true
                try {
                    committedObjectInfo(key)
                } catch (e: Exception) {
                    deleteQuietly(publishedDocument)
                    stagedExisting?.let {
                        restoreStagedDocument(
                            document = it,
                            fileName = fileName,
                        )
                    }
                    throw e
                }
            } catch (e: Exception) {
                if (!tempPublished) {
                    stagedExisting?.let {
                        restoreStagedDocument(
                            document = it,
                            fileName = fileName,
                        )
                    }
                }
                throw e
            }
            stagedExisting?.let(::deleteQuietly)
            info
        } catch (e: Exception) {
            if (!tempPublished) {
                deleteQuietly(tempDocument)
            }
            throw e
        }
    }

    private fun stageExistingDocument(
        document: AndroidTreeDocumentEntry,
        fileName: String,
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry = documentClient.renameDocument(
        document = document,
        displayName = "$fileName.${System.nanoTime()}.old.tmp",
    ) ?: throw BackupObjectStoreException.Transient(
        operation = BackupObjectStoreOperation.Write,
        key = key,
        cause = IOException("Document provider failed to stage existing backup object."),
    )

    private fun restoreStagedDocument(
        document: AndroidTreeDocumentEntry,
        fileName: String,
    ) {
        runCatching {
            documentClient.renameDocument(
                document = document,
                displayName = fileName,
            )
        }
    }

    override suspend fun list(
        prefix: BackupObjectKeyPrefix,
        cursor: BackupListCursor?,
    ): BackupObjectListPage = translate(
        operation = BackupObjectStoreOperation.List,
        key = null,
    ) {
        val items = mutableListOf<BackupObjectInfo>()
        collectFiles(
            directory = documentClient.root,
            pathPrefix = "",
            result = items,
            filterPrefix = prefix.value,
        )
        BackupObjectListPage(
            items = items.sortedBy { it.key.value },
        )
    }

    override suspend fun delete(
        key: BackupObjectKey,
    ) {
        translate(
            operation = BackupObjectStoreOperation.Delete,
            key = key,
        ) {
            val document = resolveDocument(key)
                ?.takeUnless { it.isDirectory }
                ?: return@translate
            if (!documentClient.deleteDocument(document)) {
                throw BackupObjectStoreException.PermissionDenied(
                    operation = BackupObjectStoreOperation.Delete,
                    key = key,
                )
            }
            deleteEmptyParents(key.parts().dropLast(1))
        }
    }

    private fun publishTempFile(
        tempDocument: AndroidTreeDocumentEntry,
        fileName: String,
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry {
        // Publish by rename only. The object-store contract forbids partial
        // bytes at the final key, and a copy fallback would expose the final
        // document before the copy completed.
        val renamedDocument = documentClient.renameDocument(
            document = tempDocument,
            displayName = fileName,
        ) ?: throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = IOException("Document provider failed to publish backup object."),
        )
        if (!renamedDocument.isDirectory && renamedDocument.name == fileName) {
            return renamedDocument
        }
        // DocumentsProvider#createDocument/renameDocument may alter displayName
        // to satisfy provider constraints, such as avoiding name conflicts:
        // https://developer.android.com/reference/android/provider/DocumentsProvider
        deleteQuietly(renamedDocument)
        throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = IOException(
                "Document provider published backup object as '${renamedDocument.name}'.",
            ),
        )
    }

    private suspend fun writeDocument(
        document: AndroidTreeDocumentEntry,
        key: BackupObjectKey,
        write: suspend (kotlinx.io.Sink) -> Unit,
    ) {
        val output = documentClient.openOutputStream(document, WRITE_MODE)
            ?: throw providerUnavailable(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                message = "Document provider returned null output stream.",
            )
        output.useBufferedSink(write)
    }

    private fun ensureDirectory(
        parts: List<String>,
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry {
        var current = documentClient.root
        parts.forEach { part ->
            val existing = findChild(current, part)
            current = when {
                existing == null -> createDirectory(
                    parent = current,
                    name = part,
                    key = key,
                )

                existing.isDirectory -> existing

                else -> throw BackupObjectStoreException.AlreadyExists(key)
            }
        }
        return current
    }

    private fun resolveDocument(
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry? {
        var current = documentClient.root
        key.parts().forEach { part ->
            current = findChild(current, part) ?: return null
        }
        return current
    }

    private fun resolveDirectory(
        parts: List<String>,
    ): AndroidTreeDocumentEntry? {
        var current = documentClient.root
        parts.forEach { part ->
            current = findChild(current, part)
                ?.takeIf { it.isDirectory }
                ?: return null
        }
        return current
    }

    private fun findChild(
        parent: AndroidTreeDocumentEntry,
        name: String,
    ): AndroidTreeDocumentEntry? = listChildren(parent)
        .firstOrNull { it.name == name }

    private fun listChildren(
        parent: AndroidTreeDocumentEntry,
    ): List<AndroidTreeDocumentEntry> = documentClient.listChildren(parent)

    private fun createDirectory(
        parent: AndroidTreeDocumentEntry,
        name: String,
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry {
        val document = documentClient.createDirectory(
            parent = parent,
            displayName = name,
        ) ?: throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Write,
            key = key,
        )
        if (document.isDirectory && document.name == name) {
            return document
        }
        // DocumentsProvider#createDocument may return a different display name;
        // backup keys require exact path segments, so alternate names are not
        // valid publications.
        // https://developer.android.com/reference/android/provider/DocumentsProvider
        deleteQuietly(document)
        throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = IOException(
                "Document provider created directory as '${document.name}'.",
            ),
        )
    }

    private fun createFile(
        parent: AndroidTreeDocumentEntry,
        name: String,
        key: BackupObjectKey,
    ): AndroidTreeDocumentEntry {
        val document = documentClient.createFile(
            parent = parent,
            mimeType = MIME_TYPE_BACKUP_OBJECT,
            displayName = name,
        ) ?: throw BackupObjectStoreException.PermissionDenied(
            operation = BackupObjectStoreOperation.Write,
            key = key,
        )
        if (!document.isDirectory && document.name == name) {
            return document
        }
        // DocumentsProvider#createDocument may return a different display name;
        // backup keys require exact path segments, so alternate names are not
        // valid publications.
        // https://developer.android.com/reference/android/provider/DocumentsProvider
        deleteQuietly(document)
        throw BackupObjectStoreException.Transient(
            operation = BackupObjectStoreOperation.Write,
            key = key,
            cause = IOException(
                "Document provider created backup object as '${document.name}'.",
            ),
        )
    }

    private fun committedObjectInfo(
        key: BackupObjectKey,
    ): BackupObjectInfo {
        val document = resolveDocument(key)
            ?.takeUnless { it.isDirectory }
            ?: throw providerUnavailable(
                operation = BackupObjectStoreOperation.Write,
                key = key,
                message = "Backup object '${key.value}' was not visible after writing.",
            )
        return document.toObjectInfo(key)
    }

    private fun collectFiles(
        directory: AndroidTreeDocumentEntry,
        pathPrefix: String,
        result: MutableList<BackupObjectInfo>,
        filterPrefix: String,
    ) {
        listChildren(directory).forEach { child ->
            val keyValue = pathPrefix + child.name
            if (child.isDirectory) {
                collectFiles(
                    directory = child,
                    pathPrefix = "$keyValue/",
                    result = result,
                    filterPrefix = filterPrefix,
                )
            } else if (keyValue.startsWith(filterPrefix)) {
                result += child.toObjectInfo(BackupObjectKey(keyValue))
            }
        }
    }

    private fun deleteEmptyParents(
        parts: List<String>,
    ) {
        for (length in parts.size downTo 1) {
            val directory = resolveDirectory(parts.take(length)) ?: return
            if (listChildren(directory).isNotEmpty()) {
                return
            }
            documentClient.deleteDocument(directory)
        }
    }

    private fun deleteQuietly(
        document: AndroidTreeDocumentEntry,
    ) {
        runCatching {
            documentClient.deleteDocument(document)
        }
    }

    private fun providerUnavailable(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey?,
        message: String,
    ): BackupObjectStoreException.Transient = BackupObjectStoreException.Transient(
        operation = operation,
        key = key,
        cause = IOException(message),
    )

    private fun InputStream.toBackupReadSource(
        key: BackupObjectKey,
    ): Source {
        val upstream = asSource()
        return object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long = translate(
                operation = BackupObjectStoreOperation.Read,
                key = key,
            ) {
                upstream.readAtMostTo(sink, byteCount)
            }

            override fun close() {
                translate(
                    operation = BackupObjectStoreOperation.Read,
                    key = key,
                ) {
                    upstream.close()
                }
            }
        }.buffered()
    }

    private inline fun <T> translate(
        operation: BackupObjectStoreOperation,
        key: BackupObjectKey?,
        block: () -> T,
    ): T = try {
        block()
    } catch (e: BackupObjectStoreException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw key
            ?.let { BackupObjectStoreException.NotFound(it, operation, e) }
            ?: BackupObjectStoreException.PermissionDenied(operation, cause = e)
    } catch (e: SecurityException) {
        throw BackupObjectStoreException.PermissionDenied(
            operation = operation,
            key = key,
            cause = e,
        )
    } catch (e: AndroidTreeDocumentProviderException) {
        throw e.toBackupObjectStoreException(
            operation = operation,
            key = key,
        )
    } catch (e: IOException) {
        throw BackupObjectStoreException.Transient(
            operation = operation,
            key = key,
            cause = e,
        )
    }

    private fun BackupObjectKey.parts(): List<String> = value.split('/')

    private fun AndroidTreeDocumentEntry.toObjectInfo(
        key: BackupObjectKey,
    ): BackupObjectInfo = BackupObjectInfo(
        key = key,
        size = size,
        updatedAt = updatedAt,
    )

    companion object {
        private const val MIME_TYPE_BACKUP_OBJECT = "application/octet-stream"

        // ContentResolver documents "w" as write-only and "wt" as write plus
        // truncate, which is what temporary object writes require.
        // https://developer.android.com/reference/android/content/ContentResolver
        private const val WRITE_MODE = "wt"
    }
}

internal class AndroidTreeDocumentProviderException(
    val providerCause: RuntimeException,
) : RuntimeException(providerCause)

internal interface AndroidTreeDocumentClient {
    val root: AndroidTreeDocumentEntry

    fun listChildren(
        parent: AndroidTreeDocumentEntry,
    ): List<AndroidTreeDocumentEntry>

    fun createDirectory(
        parent: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry?

    fun createFile(
        parent: AndroidTreeDocumentEntry,
        mimeType: String,
        displayName: String,
    ): AndroidTreeDocumentEntry?

    fun deleteDocument(
        document: AndroidTreeDocumentEntry,
    ): Boolean

    fun renameDocument(
        document: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry?

    fun openInputStream(
        document: AndroidTreeDocumentEntry,
    ): InputStream?

    fun openOutputStream(
        document: AndroidTreeDocumentEntry,
        mode: String,
    ): OutputStream?
}

internal data class AndroidTreeDocumentEntry(
    val id: String,
    val uri: Uri?,
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val updatedAt: Instant?,
)

private class AndroidTreeDocumentsContractClient(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
) : AndroidTreeDocumentClient {
    override val root: AndroidTreeDocumentEntry = run {
        val id = DocumentsContract.getTreeDocumentId(treeUri)
        AndroidTreeDocumentEntry(
            id = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
            name = "",
            isDirectory = true,
            size = null,
            updatedAt = null,
        )
    }

    override fun listChildren(
        parent: AndroidTreeDocumentEntry,
    ): List<AndroidTreeDocumentEntry> = callProvider {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parent.id,
        )
        val cursor = contentResolver.query(
            childrenUri,
            DOCUMENT_PROJECTION,
            null,
            null,
            null,
        ) ?: throw IOException("Document provider returned null child cursor.")
        cursor.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toDocumentEntry())
                }
            }
        }
    }

    override fun createDirectory(
        parent: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry? {
        val parentUri = parent.requireUri()
        return callProvider {
            DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                displayName,
            )?.queryDocumentEntry()
        }
    }

    override fun createFile(
        parent: AndroidTreeDocumentEntry,
        mimeType: String,
        displayName: String,
    ): AndroidTreeDocumentEntry? {
        val parentUri = parent.requireUri()
        return callProvider {
            DocumentsContract.createDocument(
                contentResolver,
                parentUri,
                mimeType,
                displayName,
            )?.queryDocumentEntry()
        }
    }

    override fun deleteDocument(
        document: AndroidTreeDocumentEntry,
    ): Boolean {
        val documentUri = document.requireUri()
        return callProvider {
            DocumentsContract.deleteDocument(
                contentResolver,
                documentUri,
            )
        }
    }

    override fun renameDocument(
        document: AndroidTreeDocumentEntry,
        displayName: String,
    ): AndroidTreeDocumentEntry? {
        val documentUri = document.requireUri()
        return callProvider {
            DocumentsContract.renameDocument(
                contentResolver,
                documentUri,
                displayName,
            )?.queryDocumentEntry()
        }
    }

    override fun openInputStream(
        document: AndroidTreeDocumentEntry,
    ): InputStream? {
        val documentUri = document.requireUri()
        return callProvider {
            contentResolver.openInputStream(documentUri)
        }
    }

    override fun openOutputStream(
        document: AndroidTreeDocumentEntry,
        mode: String,
    ): OutputStream? {
        val documentUri = document.requireUri()
        return callProvider {
            contentResolver.openOutputStream(documentUri, mode)
        }
    }

    private fun AndroidTreeDocumentEntry.requireUri(): Uri = requireNotNull(uri) {
        "Android tree document entry '${id}' does not have a URI."
    }

    private fun Uri.queryDocumentEntry(): AndroidTreeDocumentEntry {
        // DocumentsContract.renameDocument may return a URI with a new document
        // ID, and create/rename may also change the display name; query the
        // returned URI so callers see the provider's actual metadata.
        // https://developer.android.com/reference/android/provider/DocumentsContract
        val cursor = contentResolver.query(
            this,
            DOCUMENT_PROJECTION,
            null,
            null,
            null,
        ) ?: throw IOException("Document provider returned null document cursor.")
        cursor.use { cursor ->
            if (!cursor.moveToFirst()) {
                throw IOException("Document provider returned empty document cursor.")
            }
            return cursor.toDocumentEntry()
        }
    }

    private fun Cursor.toDocumentEntry(): AndroidTreeDocumentEntry {
        val id = getString(column(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
        val name = getString(column(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
        val mimeType = getString(column(DocumentsContract.Document.COLUMN_MIME_TYPE))
        val size = getLongOrNull(DocumentsContract.Document.COLUMN_SIZE)
        val lastModified = getLongOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        return AndroidTreeDocumentEntry(
            id = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
            name = name,
            isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
            size = size,
            updatedAt = lastModified
                ?.takeIf { it > 0L }
                ?.let(Instant::fromEpochMilliseconds),
        )
    }

    private fun Cursor.getLongOrNull(
        name: String,
    ): Long? {
        val index = getColumnIndex(name)
        if (index < 0 || isNull(index)) {
            return null
        }
        return getLong(index)
    }

    private fun Cursor.column(
        name: String,
    ): Int = getColumnIndexOrThrow(name)

    private inline fun <T> callProvider(
        block: () -> T,
    ): T = try {
        block()
    } catch (e: SecurityException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        throw AndroidTreeDocumentProviderException(e)
    }

    companion object {
        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private fun AndroidTreeDocumentProviderException.toBackupObjectStoreException(
    operation: BackupObjectStoreOperation,
    key: BackupObjectKey?,
): BackupObjectStoreException {
    val cause = providerCause
    return when (cause) {
        is IllegalArgumentException,
        is UnsupportedOperationException -> BackupObjectStoreException.PermissionDenied(
            operation = operation,
            key = key,
            cause = cause,
        )

        else -> BackupObjectStoreException.Transient(
            operation = operation,
            key = key,
            cause = cause,
        )
    }
}

class AndroidTreeBackupObjectStoreFactory(
    private val context: Context,
) : BackupObjectStoreFactory {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance(),
    )

    override suspend fun open(
        store: BackupStoreConfig,
    ): BackupObjectStore {
        val localStore = requireNotNull(store as? BackupStoreConfig.Local) {
            "Backup local store configuration is required."
        }
        val repositoryPath = requireNotNull(localStore.path) {
            "Backup repository path is not configured."
        }
        return AndroidTreeBackupObjectStore(
            contentResolver = context.contentResolver,
            treeUri = repositoryPath.toUri(),
        )
    }
}
