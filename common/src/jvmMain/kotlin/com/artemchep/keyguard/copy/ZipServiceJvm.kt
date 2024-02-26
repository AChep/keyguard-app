package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.service.zip.ZipConfig
import com.artemchep.keyguard.common.service.zip.ZipEntry
import com.artemchep.keyguard.common.service.zip.ZipService
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.kodein.di.DirectDI
import java.io.OutputStream

class ZipServiceJvm(
) : ZipService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun zip(
        outputStream: OutputStream,
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
                    val inputStream = entry.stream()
                    inputStream.use {
                        it.copyTo(zipStream)
                    }
                } finally {
                    zipStream.closeEntry()
                }
            }
        }
    }

    private fun createZipStream(
        config: ZipConfig,
        outputStream: OutputStream,
    ): ZipOutputStream {
        return if (config.encryption != null) {
            val password = config.encryption.password
                .toCharArray()
            ZipOutputStream(outputStream, password)
        } else {
            ZipOutputStream(outputStream)
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
