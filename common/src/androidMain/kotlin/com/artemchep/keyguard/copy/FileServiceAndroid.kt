package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.DocumentsContract
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

            else -> {
                var stream: InputStream? = null
                try {
                    stream = readFromFile(uri)
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
        }
    }

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = uri.toUri()
        return context.contentResolver.openInputStream(parsedUri)!!
    }

    override fun writeToFile(uri: String): OutputStream {
        val parsedUri = uri.toUri()
        return context.contentResolver.openOutputStream(parsedUri)!!
    }
}
