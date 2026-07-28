package com.artemchep.keyguard.common.usecase.impl.benchmark

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.service.tld.impl.TldServiceImpl
import com.artemchep.keyguard.provider.bitwarden.usecase.CipherUrlCheckImpl

internal class CipherUrlCheckBenchmarkFixtures(
    private val operationCount: Int = DEFAULT_OPERATION_COUNT,
) {
    private val check = CipherUrlCheckImpl(
        tldService = TldServiceImpl(
            textService = BenchmarkTldTextService,
            logRepository = BenchmarkLogRepository,
        ),
    )
    private val equivalentDomains = EquivalentDomains(
        domains = mapOf(
            "example.com" to listOf("example.com", "example.net"),
            "example.net" to listOf("example.com", "example.net"),
            "acme.com" to listOf("acme.com", "acme.co.uk"),
            "acme.co.uk" to listOf("acme.com", "acme.co.uk"),
        ),
    )
    private val noEquivalentDomains = EquivalentDomains(emptyMap())

    private val domainOperations = listOf(
        operation("example.com", "https://login.example.com/path?q=1", Domain, true, useDefault = true),
        operation("https://secure.example.com/login", "http://login.example.com:8443/path", Domain, true),
        operation("login.example.net", "https://login.example.com/path", Domain, true),
        operation("login.unrelated.org", "https://login.example.com/path", Domain, false),
        operation("evil-example.com", "https://example.com/path", Domain, false),
        operation("EXAMPLE.COM", "https://LOGIN.EXAMPLE.COM/path", Domain, true),
        operation("acme.co.uk", "https://portal.acme.co.uk/login", Domain, true),
        operation("acme.com", "https://portal.acme.co.uk/login", Domain, true),
        operation("127.0.0.1", "http://127.0.0.1:8080/path", Domain, true, noEquivalentDomains),
        operation("androidapp://com.example", "androidapp://com.example", Domain, true, noEquivalentDomains),
        operation("iosapp://com.example", "iosapp://com.example.debug", Domain, false, noEquivalentDomains),
    )

    private val hostOperations = listOf(
        operation("login.example.com", "https://login.example.com/path?q=1", Host, true, useDefault = true),
        operation("http://login.example.com", "http://login.example.com/path", Host, true),
        operation("LOGIN.EXAMPLE.COM", "https://login.example.com/path", Host, true),
        operation("login.example.net", "https://login.example.com/path", Host, true),
        operation("app.example.net", "https://login.example.com/path", Host, false),
        operation("login.example.com", "https://example.com/path", Host, false),
        operation("login.example.com", "https://login.example.com:8443/path", Host, true, noEquivalentDomains),
        operation("login.example.com:9443", "https://login.example.com:8443/path", Host, false, noEquivalentDomains),
        operation("login.example.com:8443", "https://login.example.com:8443/path", Host, true, noEquivalentDomains),
        operation("127.0.0.1:8080", "http://127.0.0.1:8080/path", Host, true, noEquivalentDomains),
        operation("[2001:db8::1]:8080", "http://[2001:db8::1]:8080/path", Host, true, noEquivalentDomains),
        operation("androidapp://com.example", "androidapp://com.example", Host, true, noEquivalentDomains),
    )

    private val startsWithOperations = listOf(
        operation(
            "https://example.com/path",
            "https://example.com/path?q=1",
            StartsWith,
            true,
            useDefault = true,
        ),
        operation("https://example.com/admin", "https://example.com/path?q=1", StartsWith, false),
        operation("https://login.example.net/path", "https://login.example.com/path?q=1", StartsWith, true),
        operation("https://login.example.net/admin", "https://login.example.com/path?q=1", StartsWith, false),
        operation("  https://example.com/path/  ", "  https://example.com/path?q=1  ", StartsWith, true),
        operation("https://example.com/path//", "https://example.com/path", StartsWith, false),
        operation("example.com/path", "example.com/path?q=1", StartsWith, false, noEquivalentDomains),
        operation("http://127.0.0.1:8080/app", "http://127.0.0.1:8080/app/login", StartsWith, true, noEquivalentDomains),
        operation("https://portal.acme.com/login", "https://portal.acme.co.uk/login?next=home", StartsWith, true),
        operation("https://example.com/path?tab=1", "https://example.com/path?tab=2", StartsWith, false),
    )

    private val exactOperations = listOf(
        operation("https://example.com/path", "https://example.com/path", Exact, true, useDefault = true),
        operation("https://example.com/path/", "https://example.com/path", Exact, true),
        operation("  https://example.com/path  ", " https://example.com/path ", Exact, true),
        operation("https://example.com/path//", "https://example.com/path", Exact, false),
        operation("https://LOGIN.example.com/path", "https://login.example.com/path", Exact, false),
        operation("https://example.com:443/path", "https://example.com/path", Exact, false),
        operation("https://login.example.net/path", "https://login.example.com/path", Exact, false),
        operation("androidapp://com.example", "androidapp://com.example", Exact, true),
        operation("127.0.0.1:8080", "127.0.0.1:8080/", Exact, true),
    )

    private val regexOperations = listOf(
        operation(
            "https://login\\.example\\.com/path",
            "HTTPS://LOGIN.EXAMPLE.COM/PATH",
            Regex,
            true,
            useDefault = true,
        ),
        operation("https://login\\.example\\.net/path\\?q=1", "https://login.example.com/path?q=1", Regex, true),
        operation("https://login\\.example\\.net/admin.*", "https://login.example.com/path?q=1", Regex, false),
        operation("https://(login|auth)\\.example\\.com/.*", "https://auth.example.com/session/42", Regex, true),
        operation("login\\.example\\.com", "https://login.example.com/path", Regex, false),
        operation("https?://127\\.0\\.0\\.1:8080/.*", "http://127.0.0.1:8080/app", Regex, true, noEquivalentDomains),
        operation("androidapp://com\\.example", "androidapp://com.example", Regex, true, noEquivalentDomains),
        operation("https://portal\\.acme\\.com/login\\?next=.*", "https://portal.acme.co.uk/login?next=home", Regex, true),
        operation("https://example\\.com/path/[0-9]+", "https://example.com/path/not-a-number", Regex, false),
    )

    private val neverOperations = listOf(
        operation("https://example.com/path", "https://example.com/path", Never, false, useDefault = true),
        operation("[", "https://example.com/path", Never, false),
        operation("androidapp://com.example", "androidapp://com.example", Never, false),
        operation("127.0.0.1:8080", "http://127.0.0.1:8080/path", Never, false),
    )

    fun cases(): List<CipherUrlBenchmarkCase> {
        val modeCases = listOf(
            benchmarkCase("domain-diverse", Domain, domainOperations),
            benchmarkCase("host-diverse", Host, hostOperations),
            benchmarkCase("starts-with-diverse", StartsWith, startsWithOperations),
            benchmarkCase("exact-diverse", Exact, exactOperations),
            benchmarkCase("regex-diverse", Regex, regexOperations),
            benchmarkCase("never-diverse", Never, neverOperations),
        )
        val mixedOperations = buildList {
            val byMode = listOf(
                domainOperations,
                hostOperations,
                startsWithOperations,
                exactOperations,
                regexOperations,
                neverOperations,
            )
            val largestMode = byMode.maxOf(List<CipherUrlBenchmarkOperation>::size)
            repeat(largestMode) { index ->
                byMode.forEach { operations ->
                    operations.getOrNull(index)?.let(::add)
                }
            }
        }
        return modeCases + benchmarkCase("mixed-diverse", null, mixedOperations)
    }

    private fun benchmarkCase(
        name: String,
        matchType: DSecret.Uri.MatchType?,
        operations: List<CipherUrlBenchmarkOperation>,
    ) = CipherUrlBenchmarkCase(
        name = name,
        matchType = matchType?.name ?: "Mixed",
        operationCount = operationCount,
        scenarioCount = operations.size,
        run = {
            var matchCount = 0
            var checksum = 1L
            repeat(operationCount) { index ->
                val operation = operations[index % operations.size]
                val actual = check(
                    uri = operation.uri,
                    url = operation.targetUrl,
                    defaultMatchDetection = operation.defaultMatchDetection,
                    equivalentDomains = operation.equivalentDomains,
                ).bind()
                check(actual == operation.expected) {
                    "Unexpected ${operation.uri.match} result for '${operation.uri.uri}' and " +
                            "'${operation.targetUrl}': expected=${operation.expected}, actual=$actual"
                }
                if (actual) {
                    matchCount += 1
                }
                checksum = 31L * checksum + if (actual) index + 1L else -(index + 1L)
            }
            CipherUrlBenchmarkObservation(
                matchCount = matchCount,
                checksum = checksum,
            )
        },
    )

    private fun operation(
        savedUri: String,
        targetUrl: String,
        matchType: DSecret.Uri.MatchType,
        expected: Boolean,
        equivalentDomains: EquivalentDomains = this.equivalentDomains,
        useDefault: Boolean = false,
    ) = CipherUrlBenchmarkOperation(
        uri = DSecret.Uri(
            uri = savedUri,
            match = matchType.takeUnless { useDefault },
        ),
        targetUrl = targetUrl,
        defaultMatchDetection = matchType,
        equivalentDomains = equivalentDomains,
        expected = expected,
    )

    private companion object {
        const val DEFAULT_OPERATION_COUNT = 512

        val Domain = DSecret.Uri.MatchType.Domain
        val Host = DSecret.Uri.MatchType.Host
        val StartsWith = DSecret.Uri.MatchType.StartsWith
        val Exact = DSecret.Uri.MatchType.Exact
        val Regex = DSecret.Uri.MatchType.RegularExpression
        val Never = DSecret.Uri.MatchType.Never
    }
}

private data class CipherUrlBenchmarkOperation(
    val uri: DSecret.Uri,
    val targetUrl: String,
    val defaultMatchDetection: DSecret.Uri.MatchType,
    val equivalentDomains: EquivalentDomains,
    val expected: Boolean,
)
