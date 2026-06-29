@file:Suppress("unused")

package app.keemobile.kotpass.models

import app.keemobile.kotpass.errors.FormatError
import app.keemobile.kotpass.io.gunzip
import app.keemobile.kotpass.io.gzip
import com.artemchep.keyguard.util.foundation.crypto.sha256
import okio.ByteString
import okio.ByteString.Companion.toByteString

sealed class BinaryData(val hash: ByteString) {
    abstract val memoryProtection: Boolean
    abstract val rawContent: ByteArray

    abstract fun getContent(): ByteArray

    class Uncompressed(
        override val memoryProtection: Boolean,
        override val rawContent: ByteArray
    ) : BinaryData(sha256(rawContent).toByteString()) {
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
    ) : BinaryData(sha256(rawContent).toByteString()) {
        override fun getContent(): ByteArray = try {
            rawContent.gunzip()
        } catch (_: Exception) {
            throw FormatError.FailedCompression(
                "Failed to read from compressed binary data stream."
            )
        }
    }
}
