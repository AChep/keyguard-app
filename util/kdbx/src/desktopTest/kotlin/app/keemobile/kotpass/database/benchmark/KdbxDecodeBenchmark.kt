package app.keemobile.kotpass.database.benchmark

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.cryptography.format.BaseCiphers
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.database.decodeFromXml
import app.keemobile.kotpass.database.encodeTo
import app.keemobile.kotpass.database.header.DatabaseHeader
import app.keemobile.kotpass.database.header.KdfParameters
import com.artemchep.keyguard.nativecrypto.NativeCryptoPrimitives
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import okio.ByteString.Companion.toByteString
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Dependency-free end-to-end decode benchmark. It is excluded from the normal
 * suite and runs through `:util:kdbx:kdbxDecodeBenchmark`.
 */
class KdbxDecodeBenchmark {
    @Test
    fun compareAesDecodeModes() {
        val plaintext = ByteArray(9 * MIB) { it.toByte() }
        val key = ByteArray(32) { (it * 3).toByte() }
        val iv = ByteArray(16) { (it * 5).toByte() }
        val ciphertext = NativeCryptoPrimitives.aesCbcPkcs7Encrypt(key, iv, plaintext)
        repeat(2) {
            decryptOneShot(ciphertext, key, iv)
            decryptStreaming(ciphertext, key, iv)
        }

        val iterations = 5
        val oneShot = LongArray(iterations)
        val streaming = LongArray(iterations)
        var checksum = 0L
        repeat(iterations) { index ->
            oneShot[index] = measureNanoTime {
                checksum += decryptOneShot(ciphertext, key, iv)
            }
            streaming[index] = measureNanoTime {
                checksum += decryptStreaming(ciphertext, key, iv)
            }
        }

        println(
            "KDBX_AES_BENCH sizeMiB=9.00 " +
                "oneShotMedianMs=${oneShot.medianMilliseconds().format()} " +
                "streamingMedianMs=${streaming.medianMilliseconds().format()} " +
                "checksum=$checksum",
        )
    }

    @Test
    fun decodeRealisticVault() {
        val entryCount = System.getenv("KDBX_DECODE_BENCH_ENTRIES")?.toInt() ?: 20_000
        val iterations = System.getenv("KDBX_DECODE_BENCH_ITERATIONS")?.toInt() ?: 3
        require(entryCount > 0)
        require(iterations > 0)

        val (encoded, logicalBytes) = encodedDatabase(entryCount)
        repeat(2) { decode(encoded) }

        val samples = LongArray(iterations)
        var checksum = 0L
        repeat(iterations) { index ->
            forceGc()
            samples[index] = measureNanoTime {
                checksum += decode(encoded)
            }
        }

        forceGc()
        val (peakHeapBytes, decodedEntries) = peakAdditionalHeap { decode(encoded).toLong() }
        checksum += decodedEntries
        printResult(entryCount, logicalBytes, encoded.size, samples, peakHeapBytes, checksum)
    }

    private fun decode(encoded: ByteArray): Int {
        val source = ByteArrayRawSource(encoded).buffered()
        return KeePassDatabase.decode(source, CREDENTIALS).content.group.entries.size
    }

    private fun decryptOneShot(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): Long = NativeCryptoPrimitives.aesCbcPkcs7Decrypt(key, iv, ciphertext).let { output ->
        try {
            output.size.toLong()
        } finally {
            output.fill(0)
        }
    }

    private fun decryptStreaming(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
    ): Long {
        val session = BaseCiphers.Aes.createDecryptor(key, iv)
        var outputBytes = 0L
        try {
            var offset = 0
            while (offset < ciphertext.size) {
                val length = minOf(64 * 1024, ciphertext.size - offset)
                val output = session.update(ciphertext, offset, length)
                outputBytes += output.size
                output.fill(0)
                offset += length
            }
            val output = session.finish()
            outputBytes += output.size
            output.fill(0)
            return outputBytes
        } finally {
            session.close()
        }
    }

    private fun encodedDatabase(entryCount: Int): Pair<ByteArray, Int> {
        val xml = vaultXml(entryCount)
        val database = KeePassDatabase.decodeFromXml(xml, CREDENTIALS) as KeePassDatabase.Ver4x
        val configured = database.copy(
            header = database.header.copy(
                compression = DatabaseHeader.Compression.None,
                kdfParameters = KdfParameters.Aes(
                    rounds = 1U,
                    seed = ByteArray(32) { it.toByte() }.toByteString(),
                ),
            ),
        )
        val sink = Buffer()
        configured.encodeTo(sink)
        return sink.readByteArray() to xml.size
    }

    private fun vaultXml(entryCount: Int): ByteArray = buildString(entryCount * 500) {
        append("<KeePassFile><Meta><Generator>Benchmark</Generator></Meta><Root>")
        append("<Group><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><Name>Root</Name>")
        repeat(entryCount) { index ->
            append("<Entry><UUID>AAAAAAAAAAAAAAAAAAAAAA==</UUID><IconID>0</IconID>")
            append("<String><Key>Title</Key><Value>Entry ").append(index).append("</Value></String>")
            append("<String><Key>UserName</Key><Value>user").append(index).append("</Value></String>")
            append("<String><Key>Password</Key><Value>correct horse battery staple</Value></String>")
            append("<String><Key>URL</Key><Value>https://example.test/path?q=")
                .append(index).append("&amp;lang=en</Value></String></Entry>")
        }
        append("</Group><DeletedObjects/></Root></KeePassFile>")
    }.encodeToByteArray()

    private fun <T> peakAdditionalHeap(block: () -> T): Pair<Long, T> {
        val running = AtomicBoolean(true)
        val peak = AtomicLong(0L)
        val monitor = thread(name = "kdbx-heap-sampler", isDaemon = true) {
            while (running.get()) {
                peak.updateAndGet { previous -> max(previous, usedHeap()) }
                Thread.sleep(1L)
            }
        }
        Thread.sleep(10L)
        val baseline = usedHeap()
        peak.set(baseline)
        val result = block()
        peak.updateAndGet { previous -> max(previous, usedHeap()) }
        running.set(false)
        monitor.join()
        return max(0L, peak.get() - baseline) to result
    }

    private fun forceGc() {
        repeat(3) {
            System.gc()
            Thread.sleep(50L)
        }
    }

    private fun usedHeap(): Long = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used

    private fun printResult(
        entryCount: Int,
        logicalBytes: Int,
        encodedBytes: Int,
        samples: LongArray,
        peakHeapBytes: Long,
        checksum: Long,
    ) {
        val medianNs = samples.sorted()[samples.size / 2]
        val logicalMiB = logicalBytes / MIB.toDouble()
        val seconds = medianNs / 1_000_000_000.0
        println(
            "KDBX_DECODE_BENCH entries=$entryCount logicalMiB=${logicalMiB.format()} " +
                "encodedMiB=${(encodedBytes / MIB.toDouble()).format()} " +
                "medianMs=${(medianNs / 1_000_000.0).format()} " +
                "throughputMiBps=${(logicalMiB / seconds).format()} " +
                "peakAdditionalHeapMiB=${(peakHeapBytes / MIB.toDouble()).format()} " +
                "checksum=$checksum",
        )
    }

    private fun Double.format(): String = String.format(Locale.ROOT, "%.2f", this)

    private fun LongArray.medianMilliseconds(): Double =
        sorted()[size / 2] / 1_000_000.0

    private class ByteArrayRawSource(private val bytes: ByteArray) : RawSource {
        private var offset = 0

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (offset == bytes.size) return -1L
            val count = minOf(byteCount.toInt(), bytes.size - offset, 64 * 1024)
            sink.write(bytes, startIndex = offset, endIndex = offset + count)
            offset += count
            return count.toLong()
        }

        override fun close() = Unit
    }

    private companion object {
        const val MIB = 1024 * 1024
        val CREDENTIALS = Credentials.from(EncryptedValue.fromString("benchmark-password"))
    }
}
