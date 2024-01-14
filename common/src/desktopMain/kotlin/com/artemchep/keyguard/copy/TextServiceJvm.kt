package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.TextService
import dev.icerock.moko.resources.FileResource
import org.kodein.di.DirectDI
import java.io.InputStream
import kotlin.io.path.Path

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
        when {
            uri.startsWith("file://") -> {
                return Path(uri)
                    .toFile()
                    .inputStream()
            }
            else -> {
                throw IllegalStateException("Unsupported URI protocol.")
            }
        }
    }
}
