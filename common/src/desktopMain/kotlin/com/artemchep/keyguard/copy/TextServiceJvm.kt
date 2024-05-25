package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.kodein.di.DirectDI
import java.io.File
import java.io.InputStream
import java.net.URI

class TextServiceJvm() : TextService {
    constructor(
        directDI: DirectDI,
    ) : this()

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun readFromResources(
        fileResource: FileResource,
    ): InputStream = Res.readBytes(fileResource.name)
        .inputStream()

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
