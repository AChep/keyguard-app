package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.openintents.openpgp.IOpenPgpService2
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * The raw OpenPGP transport: one binder call plus the output pipe it needs.
 *
 * Deliberately does not go through [org.openintents.openpgp.util.OpenPgpApi],
 * which overwrites [org.openintents.openpgp.util.OpenPgpApi.EXTRA_API_VERSION]
 * with its own compiled-in constant and therefore cannot express an API-version
 * probe of any kind. `LibraryClientCompatTest` covers that wrapper separately.
 */
class OpenPgpClient(
    private val context: Context,
    private val service: IOpenPgpService2,
) {
    class Call(
        val request: Intent,
        val result: Intent,
        val output: ByteArray?,
        val outputPipeId: Int,
        val durationMs: Long,
    )

    private val pipeIdGenerator = AtomicInteger(1)

    fun nextPipeId(): Int = pipeIdGenerator.getAndIncrement()

    /** Raw pipe allocation, so the capacity and ownership rules can be probed. */
    fun createOutputPipe(pipeId: Int): ParcelFileDescriptor? =
        service.createOutputPipe(pipeId)

    /**
     * Sends [request], streaming [input] in and draining whatever comes back out.
     *
     * The output pipe is drained on a separate thread that is started before the
     * binder call: the provider writes into the pipe while [execute] is still
     * blocked, and anything larger than the pipe buffer would otherwise deadlock.
     */
    fun execute(
        request: Intent,
        input: ByteArray?,
        outputMode: OpenPgpOutputMode,
        outputPipeIdOverride: Int? = null,
    ): Call {
        val pipeId = outputPipeIdOverride
            ?: if (outputMode == OpenPgpOutputMode.NONE) NO_PIPE else nextPipeId()
        // An overridden id is the caller asking to send a pipe id it does not
        // own, so it must not be registered with the provider first.
        val readSide = if (outputPipeIdOverride == null && pipeId != NO_PIPE) {
            createOutputPipe(pipeId)
        } else {
            null
        }
        val sink = readSide?.let { ByteArrayOutputStream() }
        val drain = readSide?.let { descriptor -> drainAsync(descriptor, sink!!) }
        val inputDescriptor = input?.let(::inputDescriptor)
        val startedAt = SystemClock.elapsedRealtime()
        val result = try {
            service.execute(request, inputDescriptor, pipeId)
        } finally {
            inputDescriptor?.closeQuietly()
        }
        val durationMs = SystemClock.elapsedRealtime() - startedAt
        drain?.join(DRAIN_TIMEOUT_MS)
        readSide?.closeQuietly()
        result.setExtrasClassLoader(context.classLoader)
        return Call(
            request = request,
            result = result,
            output = sink?.toByteArray(),
            outputPipeId = pipeId,
            durationMs = durationMs,
        )
    }

    private fun drainAsync(
        readSide: ParcelFileDescriptor,
        sink: ByteArrayOutputStream,
    ): Thread = Thread {
        runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(readSide).use { it.copyTo(sink) }
        }
    }.apply {
        isDaemon = true
        name = "openpgp-output-drain"
        start()
    }

    /**
     * Backs the input stream with a temp file rather than a pipe.
     *
     * A pipe needs a writer thread, and the negative paths here are exactly the
     * ones where the provider rejects the request without ever reading - which
     * leaves that thread blocked on a full pipe. A file descriptor is read
     * identically by the provider and has no such tail.
     */
    private fun inputDescriptor(input: ByteArray): ParcelFileDescriptor {
        val file = File.createTempFile("openpgp-input", ".bin", context.cacheDir)
        try {
            FileOutputStream(file).use { it.write(input) }
            return ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY,
            )
        } finally {
            // The descriptor keeps the inode alive; the name is not needed.
            file.delete()
        }
    }

    private fun ParcelFileDescriptor.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        const val NO_PIPE = 0
        private const val DRAIN_TIMEOUT_MS = 30_000L
    }
}
