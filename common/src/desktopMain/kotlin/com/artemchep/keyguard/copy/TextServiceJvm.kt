package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.TextService
import dev.icerock.moko.resources.FileResource
import org.kodein.di.DirectDI
import java.io.File
import java.io.InputStream
import java.net.URI

class TextServiceJvm() : TextService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun readFromResources(
        fileResource: FileResource,
    ): InputStream = fileResource.inputStream()

    private fun FileResource.inputStream() = resourcesClassLoader
        .getResourceAsStream(filePath)!!

    override fun readFromFile(uri: String): InputStream {
        val parsedUri = URI.create(uri)
        return when (parsedUri.scheme) {
            "file" -> {
                val file = parsedUri.path.let(::File)
                file.inputStream()
            }
            else -> {
                throw IllegalStateException("Unsupported URI protocol, could not read '$uri'.")
            }
        }
    }
}
