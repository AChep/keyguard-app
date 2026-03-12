package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.toSource
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.res.Res
import kotlinx.io.Source
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.kodein.di.DirectDI
import org.kodein.di.instance

class TextServiceAndroid(
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
    ): Source = Res.readBytes(fileResource.name)
        .toSource()

    override fun readFromFile(uri: String) = fileService.readFromFile(uri)
}
