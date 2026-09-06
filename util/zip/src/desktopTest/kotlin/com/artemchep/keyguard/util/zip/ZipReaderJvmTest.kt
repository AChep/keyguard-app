package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/** The JDK's own reader lists what the writer produces. */
class ZipReaderJvmTest {
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
