package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.file.FileService
import org.kodein.di.DirectDI
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

class FileServiceJvm() : FileService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun exists(uri: String): Boolean {
        val parsedUri = URI.create(uri)
        return when (parsedUri.scheme) {
            "file" -> {
                val file = parsedUri.path.let(::File)
                file.exists()
            }

            else -> {
                val msg = "Unsupported URI protocol, could not read from '$uri'."
                throw IllegalStateException(msg)
            }
        }
    }

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = URI.create(uri)
        return when (parsedUri.scheme) {
            "file" -> {
                val file = parsedUri.path.let(::File)
                file.inputStream()
            }

            else -> {
                val msg = "Unsupported URI protocol, could not read from '$uri'."
                throw IllegalStateException(msg)
            }
        }
    }

    override fun writeToFile(uri: String): OutputStream {
        val parsedUri = URI.create(uri)
        return when (parsedUri.scheme) {
            "file" -> {
                val file = parsedUri.path.let(::File)
                file.outputStream()
            }

            else -> {
                val msg = "Unsupported URI protocol, could not write to '$uri'."
                throw IllegalStateException(msg)
            }
        }
    }
}
