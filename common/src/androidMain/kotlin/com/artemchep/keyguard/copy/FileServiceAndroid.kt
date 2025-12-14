package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toFile
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream
import androidx.core.net.toUri
import com.artemchep.keyguard.common.service.file.FileService
import kotlinx.io.IOException
import java.io.OutputStream

class FileServiceAndroid(
    private val context: Context,
) : FileService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override fun exists(uri: String): Boolean {
        val parsedUri = uri.toUri()
        return when (parsedUri.scheme) {
            "file" -> {
                parsedUri.toFile()
                    .exists()
            }

            else -> exists(parsedUri)
        }
    }

    private fun exists(uri: Uri): Boolean {
        // When I create files using the document provider APIs it immediately
        // creates a 0-bytes file before returning a URI.
        runCatching {
            val size = readFileSize(uri)
            return size > 0L
        }

        var stream: InputStream? = null
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

    private fun readFileSize(uri: Uri): Long =
        context.contentResolver.query(uri, null, null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                // Get the size column
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (!cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
            }
            ?: -1L

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = uri.toUri()
        return context.contentResolver.openInputStream(parsedUri)!!
    }

    override fun writeToFile(uri: String): OutputStream {
        val parsedUri = uri.toUri()
        return context.contentResolver.openOutputStream(parsedUri)!!
    }
}
