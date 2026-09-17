package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.file.FileService
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.util.io.toSource
import kotlinx.io.Source
import org.jetbrains.compose.resources.ExperimentalResourceApi

class TextServiceAndroid(
    private val fileService: FileService,
) : TextService {

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun readFromResources(
        fileResource: FileResource,
    ): Source = Res.readBytes(fileResource.name)
        .toSource()

    override fun readFromFile(uri: String) = fileService.readFromFile(uri)
}
