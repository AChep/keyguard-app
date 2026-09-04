package com.artemchep.keyguard.util.zip

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.writeString

internal fun textEntry(name: String, text: String): ZipEntry = ZipEntry(
    name = name,
    data = ZipEntry.Data.In { text.encodeToByteArray().source() },
)

internal fun bytesEntry(name: String, bytes: ByteArray): ZipEntry = ZipEntry(
    name = name,
    data = ZipEntry.Data.In { bytes.source() },
)

internal suspend fun archive(
    password: String?,
    entries: List<ZipEntry>,
): ByteArray {
    val buffer = Buffer()
    createZipService().zip(
        outputStream = buffer,
        config = ZipConfig(
            encryption = password?.let(ZipConfig::Encryption),
        ),
        entries = entries,
    )
    return buffer.readByteArray()
}

/** Pick a [size] larger than any internal buffer. */
internal fun largePayload(size: Int): ByteArray = ByteArray(size) { index ->
    (index % 251).toByte()
}

internal fun ByteArray.source(): Source = Buffer().apply { write(this@source) }
