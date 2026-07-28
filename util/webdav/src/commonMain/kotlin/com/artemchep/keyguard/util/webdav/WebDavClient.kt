package com.artemchep.keyguard.util.webdav

import kotlinx.io.Sink
import kotlinx.io.Source

interface WebDavClient {
    suspend fun open(): WebDavOpenResult

    suspend fun stat(
        path: String,
    ): WebDavResource?

    suspend fun read(
        path: String,
        range: WebDavByteRange? = null,
    ): Source

    suspend fun write(
        path: String,
        mode: WebDavWriteMode = WebDavWriteMode.CreateOrReplace,
        bytes: ByteArray,
        precondition: WebDavWritePrecondition? = null,
    ): WebDavResource

    /**
     * The [write] callback may be invoked more than once when the client
     * has to retry or degrade the upload flow, so it must be able to
     * produce the same payload again on every invocation.
     */
    suspend fun write(
        path: String,
        mode: WebDavWriteMode = WebDavWriteMode.CreateOrReplace,
        contentLength: Long? = null,
        precondition: WebDavWritePrecondition? = null,
        write: suspend (Sink) -> Unit,
    ): WebDavResource

    suspend fun list(
        prefix: String,
    ): List<WebDavResource>

    suspend fun delete(
        path: String,
    )

    suspend fun close() {
        // no-op by default
    }
}
