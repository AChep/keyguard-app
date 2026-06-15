package com.artemchep.keyguard.common.io

import com.artemchep.keyguard.platform.toLocalPath
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LocalPathIoExtTest {
    @Test
    fun `writeText and readText round trip utf8 text`() {
        val root = createTempDirectory("local-path-io-text")
        val file = root.resolve("payload.txt").toLocalPath()
        val data = "payload Привіт"

        file.writeText(data)

        assertEquals(data, file.readText())
    }

    @Test
    fun `writeBytes and readBytes round trip bytes`() {
        val root = createTempDirectory("local-path-io-bytes")
        val file = root.resolve("payload.bin").toLocalPath()
        val data = byteArrayOf(0x00, 0x01, 0x7F, 0x40, 0x7E)

        file.writeBytes(data)

        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `read helpers fail on missing file`() {
        val root = createTempDirectory("local-path-io-missing")
        val file = root.resolve("missing.bin").toLocalPath()

        assertFailsWith<FileNotFoundException> {
            file.readText()
        }
        assertFailsWith<FileNotFoundException> {
            file.readBytes()
        }
    }
}
