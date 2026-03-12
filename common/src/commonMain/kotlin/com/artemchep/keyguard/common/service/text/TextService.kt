package com.artemchep.keyguard.common.service.text

import com.artemchep.keyguard.common.model.FileResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Source
import kotlinx.io.readString

interface TextService {
    suspend fun readFromResources(
        fileResource: FileResource,
    ): Source

    fun readFromFile(
        uri: String,
    ): Source
}

suspend fun TextService.readFromResourcesAsText(
    fileResource: FileResource,
) = withContext(Dispatchers.IO) {
    readFromResources(fileResource).useReadAsText()
}

suspend fun TextService.readFromFileAsText(
    uri: String,
) = withContext(Dispatchers.IO) {
    readFromFile(uri).useReadAsText()
}

private fun Source.useReadAsText() = use {
    readString()
}
