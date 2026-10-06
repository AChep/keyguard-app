package com.artemchep.keyguard.common.service.s3

import com.artemchep.keyguard.util.s3.S3ByteRange
import com.artemchep.keyguard.util.s3.S3Client
import com.artemchep.keyguard.util.s3.S3ClientConfig
import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3ListPage
import com.artemchep.keyguard.util.s3.S3Object
import com.artemchep.keyguard.util.s3.S3Operation
import com.artemchep.keyguard.util.s3.S3WritePrecondition
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.write

internal class InMemoryS3ClientFactory(
    val client: InMemoryS3Client = InMemoryS3Client(),
) : S3ClientFactory {
    val configs = mutableListOf<S3ClientConfig>()

    override suspend fun create(
        config: S3ClientConfig,
    ): S3Client {
        configs += config
        return client
    }
}

/** An in-memory bucket with S3 listing, range and conditional write semantics. */
internal class InMemoryS3Client(
    private val pageSize: Int = 1000,
    /** Accepts conditional writes without enforcing them, like Garage. */
    private val ignoresPreconditions: Boolean = false,
) : S3Client {
    val objects = mutableMapOf<String, ByteArray>()
    val preconditions = mutableListOf<Pair<String, S3WritePrecondition?>>()
    val deleted = mutableListOf<String>()
    var closeCalls = 0
        private set
    var listError: Exception? = null
    var getError: S3Exception? = null
    var putError: Throwable? = null

    override suspend fun headObject(
        key: String,
    ): S3Object? = objects[key]?.let { s3Object(key, it) }

    @Suppress("ThrowsCount")
    override suspend fun getObject(
        key: String,
        range: S3ByteRange?,
    ): Source {
        getError?.let { throw it }
        val bytes = objects[key]
            ?: throw S3Exception.NotFound(S3Operation.Get, key)
        val slice = if (range == null) {
            bytes
        } else {
            val end = range.length?.let { range.offset + it } ?: bytes.size.toLong()
            if (range.offset >= bytes.size || end > bytes.size) {
                throw S3Exception.InvalidRange(S3Operation.Get, key)
            }
            bytes.copyOfRange(range.offset.toInt(), end.toInt())
        }
        return Buffer().apply { write(slice) }
    }

    @Suppress("ThrowsCount")
    override suspend fun putObject(
        key: String,
        contentLength: Long,
        precondition: S3WritePrecondition?,
        body: suspend (Sink) -> Unit,
    ): S3Object {
        putError?.let { throw it }
        preconditions += key to precondition
        when (val enforced = precondition.takeUnless { ignoresPreconditions }) {
            null -> Unit
            S3WritePrecondition.IfNoneMatch -> if (key in objects) {
                throw S3Exception.AlreadyExists(S3Operation.Put, key)
            }
            is S3WritePrecondition.IfMatch -> if (headObject(key)?.etag != enforced.etag) {
                throw S3Exception.PreconditionFailed(S3Operation.Put, key)
            }
        }
        val buffer = Buffer()
        body(buffer)
        val bytes = buffer.readByteArray()
        check(bytes.size.toLong() == contentLength)
        objects[key] = bytes
        return s3Object(key, bytes)
    }

    override suspend fun listObjects(
        prefix: String,
        delimiter: String?,
        continuationToken: String?,
        maxKeys: Int?,
    ): S3ListPage {
        listError?.let { throw it }
        val entries = objects.keys
            .sorted()
            .filter { it.startsWith(prefix) }
            .map { key ->
                val rest = key.removePrefix(prefix)
                val index = delimiter?.let { rest.indexOf(it) } ?: -1
                if (index >= 0) prefix + rest.substring(0, index + delimiter!!.length) else key
            }
            .distinct()
        val start = continuationToken?.toInt() ?: 0
        val limit = minOf(maxKeys ?: pageSize, pageSize)
        val page = entries.drop(start).take(limit)
        val next = (start + limit).takeIf { it < entries.size }?.toString()
        return S3ListPage(
            objects = page
                .filter { delimiter == null || !it.endsWith(delimiter) || it in objects }
                .mapNotNull { key -> objects[key]?.let { s3Object(key, it) } },
            commonPrefixes = page.filter { delimiter != null && it.endsWith(delimiter) && it !in objects },
            nextContinuationToken = next,
        )
    }

    override suspend fun deleteObject(
        key: String,
    ) {
        deleted += key
        objects.remove(key)
    }

    override suspend fun close() {
        closeCalls += 1
    }

    private fun s3Object(
        key: String,
        bytes: ByteArray,
    ) = S3Object(
        key = key,
        size = bytes.size.toLong(),
        lastModified = null,
        etag = "\"${bytes.contentHashCode()}\"",
    )
}
