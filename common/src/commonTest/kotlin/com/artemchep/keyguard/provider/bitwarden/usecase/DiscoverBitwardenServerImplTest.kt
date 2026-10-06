package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.FileResource
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.text.TextService
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.feature.auth.common.util.extractEmailDomainOrNull
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.model.ServerDiscoveryCandidate
import com.artemchep.keyguard.util.dns.DnsLookupException
import com.artemchep.keyguard.util.dns.DnsTxtResolver
import com.artemchep.keyguard.util.dns.UnsupportedDnsLookupException
import com.artemchep.keyguard.util.io.toSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

private const val DOMAIN = "example.com"
private const val BITWARDEN_NAME = "_bitwarden.example.com"
private const val VAULTWARDEN_NAME = "_vaultwarden.example.com"
private const val SERVER_URL = "https://vault.example.com"

class DiscoverBitwardenServerImplTest {
    @Test
    fun `returns a candidate from the bitwarden record`() = runTest {
        val useCase = createUseCase(
            records = mapOf(BITWARDEN_NAME to listOf(SERVER_URL)),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(
            listOf(
                ServerDiscoveryCandidate(
                    env = ServerEnv(baseUrl = SERVER_URL),
                    sameDomain = true,
                ),
            ),
            candidates,
        )
    }

    @Test
    fun `returns a candidate from the vaultwarden record`() = runTest {
        val useCase = createUseCase(
            records = mapOf(VAULTWARDEN_NAME to listOf(SERVER_URL)),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(listOf(SERVER_URL), candidates.map { it.env.baseUrl })
    }

    @Test
    fun `queries both records and lists bitwarden first`() = runTest {
        val resolver = FakeDnsTxtResolver(
            records = mapOf(
                BITWARDEN_NAME to listOf("https://bw.example.com"),
                VAULTWARDEN_NAME to listOf("https://vw.example.com"),
            ),
        )
        val useCase = createUseCase(resolver)

        val candidates = useCase(DOMAIN).bind()

        assertEquals(setOf(BITWARDEN_NAME, VAULTWARDEN_NAME), resolver.queried.toSet())
        assertEquals(
            listOf("https://bw.example.com", "https://vw.example.com"),
            candidates.map { it.env.baseUrl },
        )
    }

    @Test
    fun `drops duplicates across records and values`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                BITWARDEN_NAME to listOf(SERVER_URL, "$SERVER_URL/"),
                VAULTWARDEN_NAME to listOf("HTTPS://VAULT.EXAMPLE.COM"),
            ),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(listOf(SERVER_URL), candidates.map { it.env.baseUrl })
    }

    @Test
    fun `drops values that are not plain https urls`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                BITWARDEN_NAME to listOf(
                    "http://vault.example.com",
                    "https://user:secret@vault.example.com",
                    "https://vault.example.com/?next=1",
                    "https://vault.example.com/#fragment",
                    "vault.example.com",
                    "v=spf1 -all",
                    "",
                ),
            ),
        )

        assertEquals(emptyList(), useCase(DOMAIN).bind())
    }

    @Test
    fun `keeps the port and the path of a server url`() = runTest {
        val useCase = createUseCase(
            records = mapOf(BITWARDEN_NAME to listOf("https://vault.example.com:8443/bitwarden/")),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals("https://vault.example.com:8443/bitwarden/", candidates.single().env.baseUrl)
    }

    @Test
    fun `returns the other record when one lookup fails`() = runTest {
        val useCase = createUseCase(
            records = mapOf(VAULTWARDEN_NAME to listOf(SERVER_URL)),
            failures = mapOf(BITWARDEN_NAME to DnsLookupException("boom")),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(listOf(SERVER_URL), candidates.map { it.env.baseUrl })
    }

    @Test
    fun `fails when a lookup fails and the other finds nothing`() = runTest {
        val useCase = createUseCase(
            failures = mapOf(BITWARDEN_NAME to DnsLookupException("boom")),
        )

        assertFailsWith<DnsLookupException> { useCase(DOMAIN).bind() }
    }

    @Test
    fun `fails on platforms without a dns api`() = runTest {
        val useCase = createUseCase(
            failures = mapOf(
                BITWARDEN_NAME to UnsupportedDnsLookupException(),
                VAULTWARDEN_NAME to UnsupportedDnsLookupException(),
            ),
        )

        assertFailsWith<UnsupportedDnsLookupException> { useCase(DOMAIN).bind() }
    }

    @Test
    fun `returns the fast record when the other lookup hangs`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                BITWARDEN_NAME to listOf(SERVER_URL),
                VAULTWARDEN_NAME to listOf("https://slow.example.com"),
            ),
            delays = mapOf(VAULTWARDEN_NAME to 60_000L),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(listOf(SERVER_URL), candidates.map { it.env.baseUrl })
    }

    @Test
    fun `flags servers outside the registrable domain of the email`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                BITWARDEN_NAME to listOf(
                    "https://vault.example.com",
                    "https://vault.hosting.net",
                ),
            ),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(listOf(true, false), candidates.map { it.sameDomain })
    }

    @Test
    fun `compares registrable domains using the public suffix list`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                "_bitwarden.mail.example.co.uk" to listOf(
                    "https://vault.example.co.uk",
                    "https://vault.other.co.uk",
                ),
            ),
        )

        val candidates = useCase("mail.example.co.uk").bind()

        assertEquals(listOf(true, false), candidates.map { it.sameDomain })
    }

    @Test
    fun `flags an ip address as outside the email domain`() = runTest {
        val useCase = createUseCase(
            records = mapOf(BITWARDEN_NAME to listOf("https://192.168.1.10:8443")),
        )

        val candidate = useCase(DOMAIN).bind().single()

        assertFalse(candidate.sameDomain)
    }

    @Test
    fun `maps the official vault hosts to their region`() = runTest {
        val useCase = createUseCase(
            records = mapOf(
                BITWARDEN_NAME to listOf("https://vault.bitwarden.eu"),
                VAULTWARDEN_NAME to listOf("https://vault.bitwarden.com/"),
            ),
        )

        val candidates = useCase(DOMAIN).bind()

        assertEquals(
            listOf(ServerEnv(region = ServerEnv.Region.EU), ServerEnv(region = ServerEnv.Region.US)),
            candidates.map { it.env },
        )
    }

    @Test
    fun `keeps an official host with a port or a path as a custom server`() = runTest {
        val useCase = createUseCase(
            records = mapOf(BITWARDEN_NAME to listOf("https://vault.bitwarden.com:8443")),
        )

        val candidate = useCase(DOMAIN).bind().single()

        assertEquals(ServerEnv(baseUrl = "https://vault.bitwarden.com:8443"), candidate.env)
    }

    @Test
    fun `extracts the email domain`() {
        assertEquals("example.com", extractEmailDomainOrNull("user@example.com"))
        assertEquals("example.com", extractEmailDomainOrNull(" User@EXAMPLE.com "))
        assertNull(extractEmailDomainOrNull("user@example.com."))
        assertNull(extractEmailDomainOrNull("user@localhost"))
        assertNull(extractEmailDomainOrNull("user@10.0.0.1"))
        assertNull(extractEmailDomainOrNull("user@127.0.0.1"))
        assertNull(extractEmailDomainOrNull("not-an-email"))
        assertNull(extractEmailDomainOrNull("user"))
        assertNull(extractEmailDomainOrNull(""))
    }

    private fun createUseCase(
        records: Map<String, List<String>> = emptyMap(),
        failures: Map<String, Throwable> = emptyMap(),
        delays: Map<String, Long> = emptyMap(),
    ) = createUseCase(
        FakeDnsTxtResolver(
            records = records,
            failures = failures,
            delays = delays,
        ),
    )

    private fun createUseCase(
        resolver: DnsTxtResolver,
    ) = DiscoverBitwardenServerImpl(
        dnsTxtResolver = resolver,
        tldService = createTldService(),
        logRepository = DiscoveryTestLogRepository,
    )

    private fun createTldService(): TldService = TldServiceImpl(
        textService = object : TextService {
            override suspend fun readFromResources(
                fileResource: FileResource,
            ): Source {
                require(fileResource == FileResource.publicSuffixList)
                return """
                    com
                    net
                    co.uk
                """.trimIndent().toSource()
            }

            override fun readFromFile(uri: String): Source = error("Not used in this test.")
        },
        logRepository = DiscoveryTestLogRepository,
    )

    private object DiscoveryTestLogRepository : LogRepository {
        override suspend fun add(
            tag: String,
            message: String,
            level: LogLevel,
        ) = Unit
    }
}

private class FakeDnsTxtResolver(
    private val records: Map<String, List<String>> = emptyMap(),
    private val failures: Map<String, Throwable> = emptyMap(),
    private val delays: Map<String, Long> = emptyMap(),
) : DnsTxtResolver {
    val queried = mutableListOf<String>()

    override suspend fun lookupTxt(name: String): List<String> {
        queried += name
        delays[name]?.let { delay(it) }
        failures[name]?.let { throw it }
        return records[name].orEmpty()
    }
}
