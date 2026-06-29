package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.artemchep.keyguard.common.service.file.FileAccessToken
import com.artemchep.keyguard.common.service.file.FileMetadata
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.file.FileServiceImpl
import com.artemchep.keyguard.feature.filepicker.AndroidFileDropStorage
import kotlin.time.Instant
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.kodein.di.DirectDI
import org.kodein.di.instance

class FileServiceAndroid(
    private val context: Context,
    private val localFileService: FileServiceImpl = FileServiceImpl(),
) : FileService {
    private companion object {
        // ContentResolver.openOutputStream(uri) uses "w", and Android docs say
        // providers may or may not truncate for that mode.
        private const val WRITE_MODE_TRUNCATE = "wt"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun exists(uri: String): Boolean {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> localFileService.exists(uri)

            else -> exists(parsedUri)
        }
    }

    private fun exists(uri: Uri): Boolean {
        val metadata = runCatching {
            queryOpenableMetadata(uri)
        }.getOrNull()
        metadata?.size?.let { size ->
            // When I create files using the document provider APIs it immediately
            // creates a 0-bytes file before returning a URI.
            return size > 0L
        }

        var stream: Source? = null
        return try {
            stream = readFromFile(uri.toString())
            true // exists
        } catch (_: Throwable) {
            false // doesn't exist
        } finally {
            try {
                stream?.close()
            } catch (_: IOException) {
                // Ignored
            }
        }
    }

    private fun queryOpenableMetadata(uri: Uri): FileMetadata? =
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null

            val lastModified = cursor.getLongOrNull(
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            FileMetadata(
                lastModified = lastModified
                    ?.takeIf { it > 0L }
                    ?.let(Instant::fromEpochMilliseconds),
                size = cursor.getLongOrNull(OpenableColumns.SIZE),
            )
        }

    private fun android.database.Cursor.getLongOrNull(
        columnName: String,
    ): Long? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) {
            return null
        }

        return getLong(index)
    }

    override fun metadata(
        uri: String,
        accessToken: FileAccessToken?,
    ): FileMetadata? {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> localFileService.metadata(
                uri = uri,
                accessToken = accessToken,
            )

            else -> runCatching {
                queryOpenableMetadata(parsedUri)
            }.getOrNull()
        }
    }

    override fun readFromFile(uri: String): Source {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> localFileService.readFromFile(uri)

            else -> {
                openInputStreamOrThrow(parsedUri)
                    .asSource()
                    .buffered()
            }
        }
    }

    override fun writeToFile(uri: String): Sink {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> localFileService.writeToFile(uri)

            else -> {
                openOutputStreamOrThrow(parsedUri, WRITE_MODE_TRUNCATE)
                    .asSink()
                    .buffered()
            }
        }
    }

    private fun openOutputStreamOrThrow(
        uri: Uri,
        mode: String,
    ) = context.contentResolver.openOutputStream(uri, mode)
        ?: throw IOException("Content provider returned null output stream for '$uri'.")

    private fun openInputStreamOrThrow(
        uri: Uri,
    ) = context.contentResolver.openInputStream(uri)
        ?: throw IOException("Content provider returned null input stream for '$uri'.")

    override fun atomicWriteToFile(
        uri: String,
        accessToken: FileAccessToken?,
        bytes: ByteArray,
    ): Boolean {
        val destination = uri.toUri()
        // SAF content:// documents cannot be renamed into place. Keep their
        // existing direct-write fallback and only use rename-over for file://.
        if (destination.scheme != "file") {
            return false
        }

        return localFileService.atomicWriteToFile(
            uri = uri,
            accessToken = accessToken,
            bytes = bytes,
        )
    }

    override fun delete(uri: String): Boolean {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> localFileService.delete(uri)
            else -> {
                runCatching {
                    context.contentResolver.delete(parsedUri, null, null) > 0
                }.getOrDefault(false)
            }
        }
    }

    override fun deleteManagedSourceFile(uri: String): Boolean =
        AndroidFileDropStorage.deleteIfManagedUri(
            context = context,
            uri = uri,
        )

    override fun atomicMove(
        sourceUri: String,
        destinationUri: String,
        accessToken: FileAccessToken?,
    ): Boolean {
        val source = sourceUri.toUri()
        val destination = destinationUri.toUri()
        // Only plain files can be moved in place. SAF content:// destinations
        // cannot be atomically replaced by path, so callers must fall back to a
        // stream copy for those.
        if (source.scheme != "file" || destination.scheme != "file") {
            return false
        }
        return localFileService.atomicMove(
            sourceUri = sourceUri,
            destinationUri = destinationUri,
            accessToken = accessToken,
        )
    }
}
