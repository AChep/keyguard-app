package com.artemchep.keyguard.util.zip

import kotlinx.io.Sink
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.FilterOutputStream
import java.io.OutputStream

internal class ZipServiceJvm : ZipService {
    override suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        // zip4j closes the stream it is handed; the sink belongs to the caller.
        val stream = NonClosingOutputStream(outputStream.asOutputStream())
        createZipStream(config, stream).use { zipStream ->
            entries.forEach { entry ->
                val entryParams = createZipParameters(
                    config = config,
                    fileName = entry.name,
                )
                zipStream.putNextEntry(entryParams)
                try {
                    zipStream.writeEntryData(entry.data)
                } finally {
                    zipStream.closeEntry()
                }
            }
        }
        outputStream.flush()
    }

    private suspend fun ZipOutputStream.writeEntryData(
        data: ZipEntry.Data,
    ) {
        val zipStream = this
        when (data) {
            is ZipEntry.Data.In -> {
                data.stream().asInputStream().use { inputStream ->
                    inputStream.copyTo(zipStream)
                }
            }

            is ZipEntry.Data.Out -> {
                val sink = zipStream.asSink().buffered()
                data.stream(sink)
                sink.flush()
            }
        }
    }

    private fun createZipStream(
        config: ZipConfig,
        outputStream: OutputStream,
    ): ZipOutputStream = if (config.encryption != null) {
        val password = config.encryption.password
            .toCharArray()
        ZipOutputStream(outputStream, password)
    } else {
        ZipOutputStream(outputStream)
    }

    private fun createZipParameters(
        config: ZipConfig,
        fileName: String,
    ): ZipParameters = ZipParameters().apply {
        compressionMethod = CompressionMethod.DEFLATE
        if (config.encryption != null) {
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            isEncryptFiles = true
        }
        fileNameInZip = fileName
    }
}

actual fun createZipService(): ZipService = ZipServiceJvm()

private class NonClosingOutputStream(
    outputStream: OutputStream,
) : FilterOutputStream(outputStream) {
    // FilterOutputStream forwards a bulk write one byte at a time.
    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
    }

    override fun close() {
        flush()
    }
}
