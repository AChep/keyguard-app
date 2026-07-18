package com.artemchep.keyguard.common.service.tld.impl.benchmark

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.shared
import com.artemchep.keyguard.common.io.sharedSoftRef
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.util.foundation.io.toSource
import kotlinx.coroutines.runBlocking
import kotlinx.io.Source
import org.jetbrains.compose.resources.ExperimentalResourceApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in benchmark for the TLD service's cold trie load and hot lookup paths.
 *
 * Run with `./gradlew :common:tldServiceBenchmark`. The suite is excluded from normal desktop tests
 * because its repeated full public-suffix-list loads are deliberately expensive.
 */
class TldServiceBenchmarkTest {
    @OptIn(ExperimentalResourceApi::class)
    @Test
    fun `benchmark cold load and hot lookup`() {
        runBlocking {
            val publicSuffixList = Res.readBytes(FileResource.publicSuffixList.name)
            val textService = BenchmarkTextService(publicSuffixList)
            val harness = TldServiceBenchmarkHarness()
            val preflightService = createService(textService)

            assertEquals("example.com", preflightService.domainOf("www.example.com"))
            assertEquals("example.co.uk", preflightService.domainOf("login.example.co.uk"))
            assertEquals("city.kawasaki.jp", preflightService.domainOf("www.city.kawasaki.jp"))
            assertEquals("example.com", preflightService.domainOf(LONG_PREFIX_LOOKUP_HOSTS.first()))
            assertEquals(
                UNKNOWN_LOOKUP_HOSTS.first(),
                preflightService.domainOf(UNKNOWN_LOOKUP_HOSTS.first()),
            )

            val directIo = ioEffect { SHARED_IO_VALUE }
            val sharedIo = directIo.shared(tag = "TldServiceBenchmark-strong")
            val sharedSoftIo = directIo.sharedSoftRef(tag = "TldServiceBenchmark-soft")
            assertEquals(SHARED_IO_VALUE, sharedIo.invoke())
            assertEquals(SHARED_IO_VALUE, sharedSoftIo.invoke())

            println(
                "$OUTPUT_PREFIX case=environment" +
                    " psl_bytes=${publicSuffixList.size}" +
                    " psl_lines=${publicSuffixList.countLines()}" +
                    " hosts=${LOOKUP_HOSTS.size}" +
                    " os=${token(System.getProperty("os.name"))}" +
                    " arch=${token(System.getProperty("os.arch"))}" +
                " jvm=${token(System.getProperty("java.version"))}",
            )

            val directIoRun = harness.measureIo(
                name = "io-direct",
                io = directIo,
            )
            val sharedIoRun = harness.measureIo(
                name = "io-shared-cached",
                io = sharedIo,
            )
            val sharedSoftIoRun = harness.measureIo(
                name = "io-shared-soft-cached",
                io = sharedSoftIo,
            )

            val coldLoad = harness.measure(
                spec = TldServiceBenchmarkSpec(
                    name = "cold-load",
                    operationsPerSample = 1,
                    warmupSamples = 2,
                    measurementSamples = 7,
                ),
            ) {
                createService(textService)
                    .domainOf(COLD_LOAD_HOST)
                    .checksum()
            }

            val hotService = createService(textService)
            hotService.domainOf(COLD_LOAD_HOST)
            val hotLookup = harness.measureLookup(
                name = "hot-lookup",
                service = hotService,
                hosts = LOOKUP_HOSTS,
            )
            val longPrefixLookup = harness.measureLookup(
                name = "hot-lookup-long-prefix",
                service = hotService,
                hosts = LONG_PREFIX_LOOKUP_HOSTS,
            )
            val unknownLookup = harness.measureLookup(
                name = "hot-lookup-unknown",
                service = hotService,
                hosts = UNKNOWN_LOOKUP_HOSTS,
            )

            listOf(
                directIoRun,
                sharedIoRun,
                sharedSoftIoRun,
                coldLoad,
                hotLookup,
                longPrefixLookup,
                unknownLookup,
            ).forEach { run ->
                assertTrue(run.samples.isNotEmpty())
                assertNotNull(
                    run.medianAllocatedBytesPerOperation,
                    "The configured JVM must expose thread allocation measurements.",
                )
            }
        }
    }

    private suspend fun TldServiceBenchmarkHarness.measureIo(
        name: String,
        io: IO<Int>,
    ) = measure(
        spec = TldServiceBenchmarkSpec(
            name = name,
            operationsPerSample = 100_000,
            warmupSamples = 4,
            measurementSamples = 8,
        ),
    ) {
        io.invoke().toLong()
    }

    private suspend fun TldServiceBenchmarkHarness.measureLookup(
        name: String,
        service: TldServiceImpl,
        hosts: List<String>,
    ): TldServiceBenchmarkRun {
        var hostIndex = 0
        return measure(
            spec = TldServiceBenchmarkSpec(
                name = name,
                operationsPerSample = 20_000,
                warmupSamples = 4,
                measurementSamples = 8,
            ),
        ) {
            val host = hosts[hostIndex]
            hostIndex = (hostIndex + 1) % hosts.size
            service.domainOf(host).checksum()
        }
    }

    private fun createService(textService: TextService) = TldServiceImpl(
        textService = textService,
        logRepository = NoOpLogRepository,
    )

    private suspend fun TldServiceImpl.domainOf(host: String): String =
        getDomainName(host).invoke()

    private class BenchmarkTextService(
        private val publicSuffixList: ByteArray,
    ) : TextService {
        override suspend fun readFromResources(fileResource: FileResource): Source {
            require(fileResource == FileResource.publicSuffixList)
            return publicSuffixList.toSource()
        }

        override fun readFromFile(uri: String): Source = error("Not used by this benchmark.")
    }

    private object NoOpLogRepository : LogRepository {
        override fun post(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit

        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }

    private companion object {
        const val OUTPUT_PREFIX = "[tld-service-benchmark]"
        const val COLD_LOAD_HOST = "accounts.example.co.uk"
        const val SHARED_IO_VALUE = 0x5a5a

        val LOOKUP_HOSTS = buildList {
            repeat(64) { index ->
                add("login-$index.example.com")
                add("api-$index.example.co.uk")
                add("tenant-$index.github.io")
                add("www.city.kawasaki.jp")
            }
        }

        val LONG_PREFIX_LOOKUP_HOSTS = List(64) { index ->
            val prefix = List(16) { label ->
                "subdomain-$label-$index"
            }.joinToString(".")
            "$prefix.example.com"
        }

        val UNKNOWN_LOOKUP_HOSTS = List(64) { index ->
            val prefix = List(16) { label ->
                "unknown-$label-$index"
            }.joinToString(".")
            "$prefix.keyguard-invalid"
        }
    }
}

private fun ByteArray.countLines(): Int = count { it == '\n'.code.toByte() } +
    if (isNotEmpty() && last() != '\n'.code.toByte()) 1 else 0

private fun String.checksum(): Long = hashCode().toLong() * 31L + length

private fun token(value: String?): String = value
    .orEmpty()
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9._-]+"), "-")
    .trim('-')
    .ifEmpty { "unknown" }
