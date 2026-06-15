package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.platform.toLocalPath
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DownloadWriterTest {
    @Test
    fun `local path writer stores bytes on disk`() {
        val root = createTempDirectory("download-writer")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        DownloadWriter.LocalPathWriter(file.toLocalPath()).writeBytes(data)

        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `sink writer writes into provided sink`() {
        val sink = Buffer()
        val data = "payload".encodeToByteArray()

        DownloadWriter.SinkWriter(sink).writeBytes(data)

        assertContentEquals(data, sink.readByteArray())
    }
}
