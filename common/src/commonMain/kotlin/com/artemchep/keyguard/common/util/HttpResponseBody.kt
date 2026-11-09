package com.artemchep.keyguard.common.util

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * Reads the body, or returns `null` if it is larger than [maxBytes]. A body that says it
 * is larger is not read at all, and of any other one at most one byte past the limit is
 * buffered, so a large body never fills the memory.
 */
internal suspend fun HttpResponse.readBodyAtMostOrNull(maxBytes: Long): ByteArray? {
    val contentLength = contentLength()
    if (contentLength != null && contentLength > maxBytes) return null
    return bodyAsChannel()
        .readBuffer(maxBytes + 1L)
        .readByteArray()
        .takeIf { it.size <= maxBytes }
}
