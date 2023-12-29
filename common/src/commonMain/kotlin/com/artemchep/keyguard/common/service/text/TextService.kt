package com.artemchep.keyguard.common.service.text

import dev.icerock.moko.resources.FileResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

interface TextService {
    fun readFromResources(
        fileResource: FileResource,
    ): InputStream
}

suspend fun TextService.readFromResourcesAsText(
    fileResource: FileResource,
) = withContext(Dispatchers.IO) {
    readFromResources(fileResource).use { inputStream ->
        inputStream
            .bufferedReader()
            .readText()
    }
}
