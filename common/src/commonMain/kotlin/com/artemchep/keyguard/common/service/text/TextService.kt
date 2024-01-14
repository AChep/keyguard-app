package com.artemchep.keyguard.common.service.text

import dev.icerock.moko.resources.FileResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

interface TextService {
    fun readFromResources(
        fileResource: FileResource,
    ): InputStream

    fun readFromFile(
        uri: String,
    ): InputStream
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

private fun InputStream.useReadAsText() = use { inputStream ->
    inputStream
        .bufferedReader()
        .readText()
}
