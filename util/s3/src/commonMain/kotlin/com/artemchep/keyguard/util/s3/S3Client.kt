package com.artemchep.keyguard.util.s3

import kotlinx.io.Sink
import kotlinx.io.Source

/**
 * A client for a single bucket of an S3-compatible service. Keys are
 * absolute within the bucket.
 */
interface S3Client {
    /**
     * Returns the object metadata, or null if there is no such object.
     * Without permission to list the bucket, AWS reports a missing object as
     * [S3Exception.PermissionDenied].
     */
    suspend fun headObject(
        key: String,
    ): S3Object?

    suspend fun getObject(
        key: String,
        range: S3ByteRange? = null,
    ): Source

    /**
     * Uploads [contentLength] bytes produced by [body] in a single request.
     *
     * The [body] callback is invoked once per upload attempt, plus once to
     * hash the payload, so it must produce the same bytes every time. A
     * failure of the [body] is rethrown as is.
     *
     * Preconditions require server-side conditional writes. Unsupported
     * conditions fail without retrying an unconditional write. An `If-Match`
     * write of a deleted object fails as [S3Exception.PreconditionFailed].
     * The returned ETag is null if the upload response does not provide one.
     *
     * A [S3Exception.Transient] failure may come after the server stored the
     * object, so a retry with the same precondition may report a conflict
     * with this very upload.
     */
    suspend fun putObject(
        key: String,
        contentLength: Long,
        precondition: S3WritePrecondition? = null,
        body: suspend (Sink) -> Unit,
    ): S3Object

    suspend fun putObject(
        key: String,
        bytes: ByteArray,
        precondition: S3WritePrecondition? = null,
    ): S3Object = putObject(
        key = key,
        contentLength = bytes.size.toLong(),
        precondition = precondition,
    ) { sink ->
        sink.write(bytes)
    }

    /**
     * Lists one page of objects whose keys start with [prefix]. With a
     * [delimiter], keys that contain it after the prefix are rolled up
     * into [S3ListPage.commonPrefixes].
     */
    suspend fun listObjects(
        prefix: String = "",
        delimiter: String? = null,
        continuationToken: String? = null,
        maxKeys: Int? = null,
    ): S3ListPage

    /** Deletes the object; a missing object is not an error. */
    suspend fun deleteObject(
        key: String,
    )

    suspend fun close() {
        // Nothing to release by default.
    }
}
