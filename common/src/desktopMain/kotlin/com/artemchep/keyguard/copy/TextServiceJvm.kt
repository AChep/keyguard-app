package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.res.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.io.InputStream

class TextServiceJvm(
    private val fileService: FileService,
) : TextService {
    constructor(
        directDI: DirectDI,
    ) : this(
        fileService = directDI.instance(),
    )

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun readFromResources(
        fileResource: FileResource,
    ): InputStream = Res.readBytes(fileResource.name)
        .inputStream()

    override fun readFromFile(uri: String) = fileService.readFromFile(uri)
}
