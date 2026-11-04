package com.artemchep.keyguard.util.age

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.SystemTemporaryDirectory

/** One member of an authenticated age-encrypted ZIP archive. */
data class AgeZipEntry(val name: String, val source: Source)

/**
 * Spools ciphertext only, authenticates the entire age stream, then reads ZIP
 * entries by seeking within the encrypted file. Owns [source].
 * Each entry source becomes invalid when advancing to the next member.
 *
 * The native session spools into [spoolDirectory] without a reachable name:
 * owner-only and unlinked at creation on POSIX, delete-on-close on Windows. A
 * killed process leaves nothing behind.
 */
@Suppress("TooGenericExceptionCaught") // Construction must release native handles on every failure.
class AgeZipReader(
    source: Source,
    secret: AgeSecret,
    spoolDirectory: String = SystemTemporaryDirectory.toString(),
    maxEncryptedBytes: Long = Long.MAX_VALUE,
    private val maxEntryBytes: Long = Long.MAX_VALUE,
    private val checkCancellation: () -> Unit = {},
) : AutoCloseable {
    private val session = try {
        AgeSession()
    } catch (e: Throwable) {
        source.close()
        throw e
    }
    private var generation = 0
    private var closed = false
    private var current: Source? = null

    init {
        try {
            source.use { input ->
                require(maxEncryptedBytes > 0L && maxEntryBytes >= 0L)
                session.call(AgeCommand.SPOOL, spoolDirectory.encodeToByteArray())
                // A source yields one segment per read; fill whole chunks to save native calls.
                val chunk = ByteArray(AGE_CHUNK_BYTES)
                var total = 0L
                while (true) {
                    checkCancellation()
                    var count = 0
                    while (count < chunk.size) {
                        val read = input.readAtMostTo(chunk, count, chunk.size)
                        if (read == -1) break
                        count += read
                    }
                    if (count == 0) break
                    if (count > maxEncryptedBytes - total) throw AgeException(AgeFailure.Limit)
                    total += count
                    session.call(AgeCommand.SPOOL_WRITE, chunk, count)
                }
            }
            val bytes = secret.encode()
            try { session.call(secret.openReaderCommand, bytes) } finally { bytes.fill(0) }
            do {
                checkCancellation()
                val verified = session.call(AgeCommand.VERIFY)
            } while (verified.singleOrNull() == 0.toByte())
            checkCancellation()
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    fun nextEntry(): AgeZipEntry? {
        check(!closed) { "Age archive is closed" }
        checkCancellation()
        current?.close()
        current = null
        generation++
        val name = session.call(AgeCommand.NEXT_ENTRY)
        if (name.isEmpty()) return null
        val entryGeneration = generation
        val entrySource = object : RawSource {
            private var pending = Buffer()
            private var ended = false
            private var total = 0L
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
                check(!closed && entryGeneration == generation) { "Archive entry is closed" }
                require(byteCount >= 0L)
                if (byteCount == 0L) return 0L
                checkCancellation()
                if (pending.size == 0L && !ended) {
                    val bytes = session.call(AgeCommand.READ)
                    try {
                        if (bytes.size > maxEntryBytes - total) throw AgeException(AgeFailure.Limit)
                        total += bytes.size
                        ended = bytes.isEmpty()
                        pending.write(bytes)
                    } finally { bytes.fill(0) }
                }
                return if (pending.size == 0L) -1L else pending.readAtMostTo(sink, byteCount)
            }
            override fun close() { pending.clear() }
        }.buffered()
        current = entrySource
        return AgeZipEntry(name.decodeToString(throwOnInvalidSequence = true), entrySource)
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        // Closing the session releases the spool, which the OS then deletes.
        try { current?.close() } finally { session.close() }
    }
}
