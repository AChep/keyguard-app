@file:Suppress("unused")

package app.keemobile.kotpass.models

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.KotlinxSourceAdapter
import app.keemobile.kotpass.io.STREAM_BUFFER_SIZE
import app.keemobile.kotpass.io.gunzip
import app.keemobile.kotpass.io.gzip
import com.artemchep.keyguard.util.foundation.crypto.createSha256
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.io.readTo
import okio.BufferedSink
import okio.ByteString
import okio.ByteString.Companion.toByteString
import okio.use

/**
 * @property hash Identity of the stored representation, including compression and memory protection.
 * This is not the SHA-256 of [getContent]; content-based lookup uses BinaryIndex.
 */
sealed class BinaryData(val hash: ByteString) {
    abstract val memoryProtection: Boolean
    abstract val rawContent: ByteArray

    abstract fun getContent(): ByteArray

    class Uncompressed(
        override val memoryProtection: Boolean,
        override val rawContent: ByteArray
    ) : BinaryData(binaryHash(compressed = false, memoryProtection, rawContent)) {
        override fun getContent(): ByteArray = rawContent

        fun toCompressed(): Compressed = try {
            Compressed(memoryProtection, getContent().gzip())
        } catch (_: Exception) {
            throw FormatError.FailedCompression("Failed to gzip binary data.")
        }
    }

    class Compressed(
        override val memoryProtection: Boolean,
        override val rawContent: ByteArray
    ) : BinaryData(binaryHash(compressed = true, memoryProtection, rawContent)) {
        override fun getContent(): ByteArray = try {
            rawContent.gunzip()
        } catch (error: FormatError) {
            // Preserve specific format errors,
            // instead of masking them behind the generic message below.
            throw error
        } catch (_: Exception) {
            throw FormatError.FailedCompression(
                "Failed to read from compressed binary data stream."
            )
        }
    }

    /**
     * Uncompressed content that is read from a source when the database is
     * encoded, instead of being held in memory. Every [openSource] call must
     * return the same bytes; encoding fails if their size has changed.
     *
     * KDBX 4 encoding streams the content into the inner header. Other paths,
     * such as KDBX 3.1 and plain XML, read the content into memory.
     */
    class Streamed private constructor(
        override val memoryProtection: Boolean,
        val size: Long,
        hash: ByteString,
        private val openSource: () -> Source,
    ) : BinaryData(hash) {
        override val rawContent: ByteArray
            get() = getContent()

        override fun getContent(): ByteArray = openSource().use { it.readByteArray() }

        internal fun writeContentTo(sink: BufferedSink) {
            val written = openSource().use { source ->
                KotlinxSourceAdapter(source).use { adapter ->
                    sink.writeAll(adapter)
                }
            }
            check(written == size) {
                "Binary content size changed from $size to $written bytes."
            }
        }

        /**
         * Computes the size and hash of the content as it is written, so
         * [build] does not read it again. The hash matches the hash of an
         * [Uncompressed] binary with the same content.
         */
        class Builder(
            private val memoryProtection: Boolean,
        ) : AutoCloseable {
            private val digest = createSha256().apply {
                val tag = binaryHashTag(
                    compressed = false,
                    memoryProtection = memoryProtection,
                )
                update(byteArrayOf(tag))
            }
            private val buffer = ByteArray(STREAM_BUFFER_SIZE)
            private var size = 0L

            /**
             * Returns a sink that writes the content to [output].
             * Closing the returned sink does not close [output].
             */
            fun sink(output: Sink): Sink = object : RawSink {
                override fun write(source: Buffer, byteCount: Long) {
                    var remaining = byteCount
                    while (remaining > 0L) {
                        val length = minOf(remaining, buffer.size.toLong()).toInt()
                        source.readTo(buffer, 0, length)
                        update(length)
                        output.write(buffer, 0, length)
                        remaining -= length
                    }
                }

                override fun flush() = output.flush()

                override fun close() = Unit
            }.buffered()

            /**
             * Creates the binary, every [openSource] call must
             * return the content that was written.
             */
            fun build(
                openSource: () -> Source,
            ): Streamed = Streamed(
                memoryProtection = memoryProtection,
                size = size,
                hash = digest.doFinal().toByteString(),
                openSource = openSource,
            )

            internal fun readFrom(source: Source) {
                var read = source.readAtMostTo(buffer)
                while (read != -1) {
                    update(read)
                    read = source.readAtMostTo(buffer)
                }
            }

            private fun update(length: Int) {
                digest.update(buffer, 0, length)
                size += length
            }

            override fun close() {
                buffer.fill(0)
                digest.close()
            }
        }

        companion object {
            /**
             * Reads the content once to compute its size and hash. The hash
             * matches the hash of an [Uncompressed] binary with the same content.
             */
            fun create(
                memoryProtection: Boolean,
                openSource: () -> Source,
            ): Streamed = Builder(memoryProtection).use { builder ->
                openSource().use(builder::readFrom)
                builder.build(openSource)
            }
        }
    }
}

private fun binaryHash(
    compressed: Boolean,
    memoryProtection: Boolean,
    content: ByteArray,
): ByteString =
    createSha256().use { digest ->
        digest.update(byteArrayOf(binaryHashTag(compressed, memoryProtection)))
        digest.update(content)
        digest.doFinal().toByteString()
    }

// Keep protection variants distinct when deduplicating, while retaining the
// existing tags (0/1) for unprotected binaries.
private fun binaryHashTag(
    compressed: Boolean,
    memoryProtection: Boolean,
): Byte {
    val tag = (if (compressed) 1 else 0) or (if (memoryProtection) 2 else 0)
    return tag.toByte()
}
