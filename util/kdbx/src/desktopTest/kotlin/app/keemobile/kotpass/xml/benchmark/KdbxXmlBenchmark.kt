package app.keemobile.kotpass.xml.benchmark

import app.keemobile.kotpass.cryptography.EncryptionSaltGenerator
import app.keemobile.kotpass.models.DatabaseContent
import app.keemobile.kotpass.models.FormatVersion
import app.keemobile.kotpass.models.XmlContext
import app.keemobile.kotpass.xml.DefaultXmlContentParser
import app.keemobile.kotpass.xml.marshalContent
import app.keemobile.kotpass.xml.unmarshalContent
import kotlin.test.Test
import kotlin.time.measureTime

/**
 * A deliberately dependency-free macro benchmark. It is excluded from the
 * normal suite and runs through `:util:kdbx:kdbxXmlBenchmark`.
 */
class KdbxXmlBenchmark {
    @Test
    fun parseAndWriteRealisticVaultSizes() {
        val configuredEntryCount = System.getenv("KDBX_XML_BENCH_ENTRIES")?.toInt()
        val entryCounts = configuredEntryCount?.let(::listOf) ?: listOf(100, 1_000, 10_000)
        val iterations = System.getenv("KDBX_XML_BENCH_ITERATIONS")?.toInt() ?: 5
        val mode = System.getenv("KDBX_XML_BENCH_MODE") ?: "both"
        require(iterations > 0)
        require(mode == "parse" || mode == "write" || mode == "both")

        for (entryCount in entryCounts) {
            val xml = vaultXml(entryCount)
            repeat(2) { parse(xml) }

            val parseDuration = if (mode != "write") {
                measureTime { repeat(iterations) { parse(xml) } }
            } else {
                null
            }
            val content = parse(xml)
            val writeDuration = if (mode != "parse") {
                measureTime {
                    repeat(iterations) {
                        DefaultXmlContentParser.marshalContent(plainContext(), content)
                    }
                }
            } else {
                null
            }
            val mib = xml.size / (1024.0 * 1024.0)
            println(
                "KDBX XML entries=$entryCount sizeMiB=${mib.format(2)} " +
                    "parseAvgMs=${parseDuration?.averageMilliseconds(iterations)?.format(2)} " +
                    "writeAvgMs=${writeDuration?.averageMilliseconds(iterations)?.format(2)}"
            )
        }
    }

    private fun parse(xml: ByteArray): DatabaseContent {
        val innerEncryption = EncryptionSaltGenerator.ChaCha20(byteArrayOf())
        return DefaultXmlContentParser.unmarshalContent(xml, innerEncryption) {
            XmlContext.Decode(
                version = FormatVersion(4, 1),
                encryption = innerEncryption,
                binaries = emptyMap(),
            )
        }
    }

    private fun plainContext() = XmlContext.Encode.Plain(
        version = FormatVersion(4, 1),
        binaries = emptyMap(),
        memoryProtectionFlags = emptySet(),
    )

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

    private fun Double.format(decimals: Int): String =
        java.lang.String.format(java.util.Locale.ROOT, "%.${decimals}f", this)

    private fun kotlin.time.Duration.averageMilliseconds(iterations: Int): Double =
        inWholeMicroseconds / (iterations * 1_000.0)
}
