package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import kotlinx.io.readString
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import net.lingala.zip4j.io.outputstream.ZipOutputStream as Zip4jOutputStream

/** The JDK's own reader lists what the writer produces. */
class ZipReaderJvmTest {
    @Test
    fun rejectsZipCryptoEvenWithTheCorrectPassword() {
        val archive = archiveWithEncryptionMethods(listOf(EncryptionMethod.ZIP_STANDARD))
        ZipReader(archive.source(), "password").use { reader ->
            assertFailsWith<ZipEncryptionRequiredException> { reader.nextEntry() }
        }
    }

    @Test
    fun mixedArchivesCannotSkipAnUnencryptedEntry() {
        for (encryptedFirst in listOf(false, true)) {
            val methods = if (encryptedFirst) {
                listOf(EncryptionMethod.AES, EncryptionMethod.NONE)
            } else {
                listOf(EncryptionMethod.NONE, EncryptionMethod.AES)
            }
            val archive = archiveWithEncryptionMethods(methods)
            ZipReader(archive.source(), "password").use { reader ->
                if (encryptedFirst) {
                    assertEquals("{}", reader.nextEntry()?.source?.readString())
                }
                repeat(2) {
                    assertFailsWith<ZipEncryptionRequiredException> { reader.nextEntry() }
                }
            }
        }
    }

    @Test
    fun writesAPlainArchiveTheJdkCanList() = runTest {
        val archive = archive(
            password = null,
            entries = listOf(
                textEntry("a.txt", "a"),
                textEntry("nested/b.txt", "b"),
            ),
        )

        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zipStream ->
            while (true) {
                val entry = zipStream.nextEntry ?: break
                names += entry.name
            }
        }
        assertEquals(listOf("a.txt", "nested/b.txt"), names)
    }
}

private fun archiveWithEncryptionMethods(methods: List<EncryptionMethod>): ByteArray {
    val output = ByteArrayOutputStream()
    Zip4jOutputStream(output, "password".toCharArray()).use { stream ->
        methods.forEachIndexed { index, method ->
            stream.putNextEntry(ZipParameters().apply {
                fileNameInZip = "$index.json"
                isEncryptFiles = method != EncryptionMethod.NONE
                encryptionMethod = method
            })
            stream.write("{}".encodeToByteArray())
            stream.closeEntry()
        }
    }
    return output.toByteArray()
}
