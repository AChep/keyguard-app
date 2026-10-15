package com.artemchep.keyguard.util.zip

import java.io.ByteArrayOutputStream
import kotlinx.io.readString
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.model.enums.CompressionMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipArchiveJvmTest {
    @Test
    fun previewsMixedPlainZipCryptoAndAesWithoutChangingBackupPolicy() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output, "password".toCharArray()).use { stream ->
            listOf(
                EncryptionMethod.NONE,
                EncryptionMethod.ZIP_STANDARD,
                EncryptionMethod.AES,
            ).forEachIndexed { i, method ->
                stream.putNextEntry(ZipParameters().apply {
                    fileNameInZip = "$i.txt"
                    isEncryptFiles = method != EncryptionMethod.NONE
                    encryptionMethod = method
                    compressionMethod = if (i % 2 == 0) CompressionMethod.STORE else CompressionMethod.DEFLATE
                    entrySize = 5
                })
                stream.write("hello".encodeToByteArray())
                stream.closeEntry()
            }
        }
        val bytes = output.toByteArray()
        ZipArchive(bytes).use { zip ->
            zip.entries.reversed().forEach { entry ->
                assertEquals("hello", zip.openEntry(entry.id, "password", 5).use { it.readString() })
            }
        }
        ZipReader(bytes.source(), "password").use { reader ->
            assertFailsWith<ZipEncryptionRequiredException> { reader.nextEntry() }
        }
    }
}
