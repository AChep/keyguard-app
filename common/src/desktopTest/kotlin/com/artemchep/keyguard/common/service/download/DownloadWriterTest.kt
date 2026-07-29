package com.artemchep.keyguard.common.service.download

import com.artemchep.keyguard.util.io.atomic.AtomicFileDestination
import com.artemchep.keyguard.util.io.atomic.AtomicPathComponent
import com.artemchep.keyguard.util.io.atomic.AtomicRelativePath
import com.artemchep.keyguard.util.io.toLocalPath
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DownloadWriterTest {
    @Test
    fun `local path writer stores bytes on disk`() {
        val root = createTempDirectory("download-writer")
        val file = root.resolve("payload.bin")
        val data = "payload".encodeToByteArray()

        DownloadWriter.LocalPathWriter(
            destination = AtomicFileDestination(
                root = root.toLocalPath(),
                relativePath = AtomicRelativePath.fromComponents(
                    AtomicPathComponent.parse("payload.bin"),
                ),
            ),
        ).writeBytes(data)

        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `sink writer writes into provided sink`() {
        val sink = Buffer()
        val data = "payload".encodeToByteArray()

        DownloadWriter.SinkWriter(sink).writeBytes(data)

        assertContentEquals(data, sink.readByteArray())
    }

    @Test
    fun `verified source publishes atomically and reports replay progress`() {
        val root = createTempDirectory("download-writer")
        val file = root.resolve("payload.bin")
        val data = ByteArray(192 * 1024 + 7) { index -> index.toByte() }
        val progress = mutableListOf<Long>()

        localWriter(root).writeVerifiedSource(
            source = Buffer().apply { write(data) },
            onProgress = progress::add,
        )

        assertContentEquals(data, file.readBytes())
        assertEquals(data.size.toLong(), progress.last())
    }

    @Test
    fun `verified source failure preserves existing local file`() {
        val root = createTempDirectory("download-writer")
        val file = root.resolve("payload.bin")
        val original = "original".encodeToByteArray()
        file.toFile().writeBytes(original)
        var firstRead = true
        val failingSource = object : RawSource {
            override fun readAtMostTo(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                if (!firstRead) throw IOException("source failed")
                firstRead = false
                sink.write("partial".encodeToByteArray())
                return 7L
            }

            override fun close() = Unit
        }.buffered()

        assertFailsWith<IOException> {
            localWriter(root).writeVerifiedSource(failingSource)
        }

        assertContentEquals(original, file.readBytes())
    }

    private fun localWriter(
        root: java.nio.file.Path,
    ) = DownloadWriter.LocalPathWriter(
        destination = AtomicFileDestination(
            root = root.toLocalPath(),
            relativePath = AtomicRelativePath.fromComponents(
                AtomicPathComponent.parse("payload.bin"),
            ),
        ),
    )
}
