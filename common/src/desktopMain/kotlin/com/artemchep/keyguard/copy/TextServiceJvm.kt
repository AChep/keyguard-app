package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.text.TextService
import dev.icerock.moko.resources.FileResource
import org.kodein.di.DirectDI
import java.io.InputStream

class TextServiceJvm() : TextService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun readFromResources(
        fileResource: FileResource,
    ): InputStream = fileResource.inputStream()

    private fun FileResource.inputStream() = resourcesClassLoader
        .getResourceAsStream(filePath)!!
}
