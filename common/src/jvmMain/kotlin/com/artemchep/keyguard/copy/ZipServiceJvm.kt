package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.withBufferedSink
import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import kotlinx.io.Sink
import kotlinx.io.asInputStream
import kotlinx.io.asOutputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.kodein.di.DirectDI

class ZipServiceJvm(
) : ZipService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override suspend fun zip(
        outputStream: Sink,
        config: ZipConfig,
        entries: List<ZipEntry>,
    ) {
        createZipStream(config, outputStream).use { zipStream ->
            entries.forEach { entry ->
                val entryParams = createZipParameters(
                    config = config,
                    fileName = entry.name,
                )
                zipStream.putNextEntry(entryParams)
                try {
                    when (val d = entry.data) {
                        is ZipEntry.Data.In -> {
                            d.stream().asInputStream().use { inputStream ->
                                inputStream.copyTo(zipStream)
                            }
                        }

                        is ZipEntry.Data.Out -> {
                            zipStream.withBufferedSink(d.stream)
                        }
                    }
                } finally {
                    zipStream.closeEntry()
                }
            }
        }
    }

    private fun createZipStream(
        config: ZipConfig,
        outputStream: Sink,
    ): ZipOutputStream {
        val stream = outputStream.asOutputStream()
        return if (config.encryption != null) {
            val password = config.encryption.password
                .toCharArray()
            ZipOutputStream(stream, password)
        } else {
            ZipOutputStream(stream)
        }
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
