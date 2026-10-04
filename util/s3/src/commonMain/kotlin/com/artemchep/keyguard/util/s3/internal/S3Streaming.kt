package com.artemchep.keyguard.util.s3.internal

import com.artemchep.keyguard.util.s3.S3Exception
import com.artemchep.keyguard.util.s3.S3Operation
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.asSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered

/**
 * Executes a streaming request and returns the [Source] built by [transform].
 *
 * HttpClient.request() saves the complete response body before returning,
 * so the request is kept active until the source reaches EOF or closes.
 */
@Suppress("TooGenericExceptionCaught")
@OptIn(InternalCoroutinesApi::class)
internal suspend fun HttpClient.requestS3StreamingSource(
    operation: S3Operation,
    key: String,
    url: String,
    configure: HttpRequestBuilder.() -> Unit,
    transform: suspend (HttpResponse) -> Source,
): Source {
    val result = CompletableDeferred<Source>()
    val callerJob = currentCoroutineContext()[Job]
    val requestJob = launch {
        try {
            prepareRequest(url, configure).execute { response ->
                val finished = CompletableDeferred<Unit>()
                val source = transform(response).completeWith(
                    finished = finished,
                    callerJob = callerJob,
                    operation = operation,
                    key = key,
                )
                if (!result.complete(source)) {
                    source.close()
                    return@execute
                }
                finished.await()
            }
        } catch (e: S3Exception) {
            result.completeExceptionally(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.completeExceptionally(S3Exception.Transient(operation = operation, key = key, cause = e))
        }
    }
    val cancellationHandle = callerJob?.invokeOnCompletion(
        onCancelling = true,
        invokeImmediately = true,
    ) { cause ->
        if (cause != null) {
            requestJob.cancel(CancellationException("S3 caller cancelled", cause))
        }
    }
    requestJob.invokeOnCompletion { cause ->
        cancellationHandle?.dispose()
        if (cause != null) {
            // A closed client can cancel launch before its body ever runs.
            // Always release the caller waiting for response headers.
            val failure = if (callerJob?.isCancelled == true) {
                callerJob.getCancellationException()
            } else {
                S3Exception.Transient(operation = operation, key = key, cause = cause)
            }
            result.completeExceptionally(failure)
        }
    }
    return try {
        result.await()
    } catch (e: CancellationException) {
        requestJob.cancel(e)
        throw e
    }
}

/** Streams the response body, mapping transport failures to [S3Exception.Transient]. */
internal suspend fun HttpResponse.s3BodySource(
    operation: S3Operation,
    key: String,
): Source {
    val upstream = mapS3TransportException(operation, key) {
        bodyAsChannel().asSource()
    }
    return object : RawSource {
        override fun readAtMostTo(
            sink: Buffer,
            byteCount: Long,
        ): Long = mapS3TransportException(operation, key) {
            upstream.readAtMostTo(sink, byteCount)
        }

        override fun close() {
            mapS3TransportException(operation, key) {
                upstream.close()
            }
        }
    }.buffered()
}

@Suppress("TooGenericExceptionCaught")
internal inline fun <T> mapS3TransportException(
    operation: S3Operation,
    key: String?,
    block: () -> T,
): T = try {
    block()
} catch (e: S3Exception) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    throw S3Exception.Transient(
        operation = operation,
        key = key,
        cause = e,
    )
}

@Suppress("TooGenericExceptionCaught")
@OptIn(InternalCoroutinesApi::class)
private fun Source.completeWith(
    finished: CompletableDeferred<Unit>,
    callerJob: Job?,
    operation: S3Operation,
    key: String,
): Source = object : RawSource {
    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long = try {
        this@completeWith.readAtMostTo(sink, byteCount).also { read ->
            if (read == -1L) {
                finished.complete(Unit)
            }
        }
    } catch (e: Exception) {
        finished.complete(Unit)
        if (callerJob?.isCancelled == true) {
            throw callerJob.getCancellationException()
        }
        if (e !is S3Exception &&
            (e is CancellationException || e.cancellationCauseOrNull() != null)
        ) {
            // The request coroutine was torn down while the caller is
            // still active: this is a transport failure of the stream,
            // not a cancellation of the caller.
            throw S3Exception.Transient(
                operation = operation,
                key = key,
                cause = e,
            )
        }
        throw e
    }

    override fun close() {
        try {
            this@completeWith.close()
        } finally {
            finished.complete(Unit)
        }
    }
}.buffered()

private fun Throwable.cancellationCauseOrNull(): CancellationException? =
    generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
        .filterIsInstance<CancellationException>()
        .firstOrNull()
