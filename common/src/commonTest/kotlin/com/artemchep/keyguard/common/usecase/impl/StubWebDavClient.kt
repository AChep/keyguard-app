package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.util.webdav.WebDavByteRange
import com.artemchep.keyguard.util.webdav.WebDavClient
import com.artemchep.keyguard.util.webdav.WebDavOpenResult
import com.artemchep.keyguard.util.webdav.WebDavResource
import com.artemchep.keyguard.util.webdav.WebDavWriteMode
import com.artemchep.keyguard.util.webdav.WebDavWritePrecondition
import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * A [WebDavClient] whose every operation fails; test fakes extend it
 * and override only the operations they expect to be called.
 */
internal open class StubWebDavClient : WebDavClient {
    override suspend fun open(): WebDavOpenResult = notUsed()

    override suspend fun stat(
        path: String,
    ): WebDavResource? = notUsed()

    override suspend fun read(
        path: String,
        range: WebDavByteRange?,
    ): Source = notUsed()

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        bytes: ByteArray,
        precondition: WebDavWritePrecondition?,
    ): WebDavResource = notUsed()

    override suspend fun write(
        path: String,
        mode: WebDavWriteMode,
        contentLength: Long?,
        precondition: WebDavWritePrecondition?,
        write: suspend (Sink) -> Unit,
    ): WebDavResource = notUsed()

    override suspend fun list(
        prefix: String,
    ): List<WebDavResource> = notUsed()

    override suspend fun listChildren(
        collectionPath: String,
    ): List<WebDavResource> = notUsed()

    override suspend fun delete(
        path: String,
    ) {
        notUsed()
    }

    override suspend fun close() {
        notUsed()
    }

    private fun notUsed(): Nothing = error("Not used by this test.")
}
