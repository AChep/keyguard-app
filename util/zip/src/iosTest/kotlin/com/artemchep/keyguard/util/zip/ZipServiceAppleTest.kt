package com.artemchep.keyguard.util.zip

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val LARGE_ARCHIVE_ENTRY_SIZE = 256 * 1024

class ZipServiceAppleTest {
    @Test
    fun observesCancellationDuringFinalArchiveCopy() = runTest {
        var completedNormally = false
        var output: CancelOnFirstWriteSink? = null
        val zipJob = launch {
            val job = checkNotNull(currentCoroutineContext()[Job])
            val rawOutput = CancelOnFirstWriteSink { job.cancel() }
            output = rawOutput
            val payload = Random(0).nextBytes(LARGE_ARCHIVE_ENTRY_SIZE)

            createZipService().zip(
                outputStream = rawOutput.buffered(),
                config = ZipConfig(),
                entries = listOf(
                    ZipEntry(
                        name = "large.bin",
                        data = ZipEntry.Data.Out { it.write(payload) },
                    ),
                ),
            )
            completedNormally = true
        }

        zipJob.join()

        assertFalse(completedNormally)
        assertEquals(1, checkNotNull(output).writeCount)
    }
}

private class CancelOnFirstWriteSink(
    private val cancel: () -> Unit,
) : RawSink {
    private val buffer = Buffer()
    var writeCount = 0
        private set

    override fun write(source: Buffer, byteCount: Long) {
        buffer.write(source, byteCount)
        writeCount += 1
        if (writeCount == 1) {
            cancel()
        }
    }

    override fun flush() = Unit

    override fun close() = Unit
}
