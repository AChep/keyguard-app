package com.artemchep.keyguard.common.service.text

import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.util.io.readByteArrayAndClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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

/** Reads the file into a caller-owned buffer; the caller clears it after use. */
suspend fun TextService.readFromFileAsBytes(
    uri: String,
): ByteArray {
    var bytes: ByteArray? = null
    try {
        return withContext(Dispatchers.IO) {
            readFromFile(uri).readByteArrayAndClose()
                .also { bytes = it }
        }.also { bytes = null }
    } finally {
        // Cancellation can drop a result the IO dispatcher already read.
        bytes?.fill(0)
    }
}

private fun Source.useReadAsText() = use {
    readString()
}
