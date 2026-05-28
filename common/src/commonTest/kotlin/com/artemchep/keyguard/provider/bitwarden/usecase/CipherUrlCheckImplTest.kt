package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.service.tld.TldService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class CipherUrlCheckImplTest {
    private val cipherUrlCheck = CipherUrlCheckImpl(CipherUrlHostOnlyTldService)

    @Test
    fun `explicit match type overrides default match detection`() = runTest {
        assertMatches(
            savedUri = "https://login.example.com/path",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.Exact,
            defaultMatchDetection = DSecret.Uri.MatchType.Never,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `default host match detection is used when uri match is null`() = runTest {
        assertMatches(
            savedUri = "login.example.com",
            targetUrl = "https://login.example.com/path?q=1",
            match = null,
            defaultMatchDetection = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `default exact match detection is used when uri match is null`() = runTest {
        assertMatches(
            savedUri = "https://login.example.com/path",
            targetUrl = "https://login.example.com/path",
            match = null,
            defaultMatchDetection = DSecret.Uri.MatchType.Exact,
            equivalentDomains = defaultEquivalentDomains,
        )
    }

    @Test
    fun `default never match detection is used when uri match is null`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://login.example.com/path",
            targetUrl = "https://login.example.com/path",
            match = null,
            defaultMatchDetection = DSecret.Uri.MatchType.Never,
            equivalentDomains = defaultEquivalentDomains,
        )
    }

    @Test
    fun `domain match compares base domain when target url has path`() = runTest {
        assertMatches(
            savedUri = "example.com",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match ignores scheme path query and port`() = runTest {
        assertMatches(
            savedUri = "https://example.com/login",
            targetUrl = "http://login.example.com:8443/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match handles multi label public suffix domains`() = runTest {
        assertMatches(
            savedUri = "example.co.uk",
            targetUrl = "https://login.example.co.uk/path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match rejects sibling multi label public suffix domains`() = runTest {
        assertDoesNotMatch(
            savedUri = "example.com",
            targetUrl = "https://login.example.co.uk/path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match accepts trailing root dot on target host`() = runTest {
        assertMatches(
            savedUri = "example.com",
            targetUrl = "https://login.example.com./path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match accepts uppercase target host`() = runTest {
        assertMatches(
            savedUri = "example.com",
            targetUrl = "https://LOGIN.EXAMPLE.COM/path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match rejects sibling domain with common suffix characters`() = runTest {
        assertDoesNotMatch(
            savedUri = "evil-example.com",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match applies equivalent domains`() = runTest {
        assertMatches(
            savedUri = "login.example.net",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
        )
    }

    @Test
    fun `domain match rejects unrelated domains`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.unrelated.org",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
        )
    }

    @Test
    fun `domain match rejects suffix without label boundary`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.example.com",
            targetUrl = "https://ample.com",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match rejects equivalent suffix without label boundary`() = runTest {
        assertDoesNotMatch(
            savedUri = "badexample.net",
            targetUrl = "https://example.com",
            match = DSecret.Uri.MatchType.Domain,
        )
    }

    @Test
    fun `domain match uses target domain to find one way equivalent domains`() = runTest {
        assertMatches(
            savedUri = "login.example.net",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = oneWayEquivalentDomains,
        )
    }

    @Test
    fun `domain match does not reverse one way equivalent domains`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.example.com",
            targetUrl = "https://login.example.net/path?q=1",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = oneWayEquivalentDomains,
        )
    }

    @Test
    fun `domain match propagates tld lookup failures`() = runTest {
        assertCheckFails(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "example.com",
            targetUrl = "https://broken.example.com/path",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `default match detection is used when uri match is null`() = runTest {
        assertMatches(
            savedUri = "example.com",
            targetUrl = "https://login.example.com/path?q=1",
            match = null,
            defaultMatchDetection = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match uses host matching for android app urls`() = runTest {
        assertMatches(
            savedUri = "androidapp://com.example",
            targetUrl = "androidapp://com.example",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match uses host matching for ios app urls`() = runTest {
        assertMatches(
            savedUri = "iosapp://com.example.app",
            targetUrl = "iosapp://com.example.app",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `domain match rejects different android app package hosts`() = runTest {
        assertDoesNotMatch(
            savedUri = "androidapp://com.example",
            targetUrl = "androidapp://com.example.debug",
            match = DSecret.Uri.MatchType.Domain,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match compares exact host ignoring case`() = runTest {
        assertMatches(
            savedUri = "LOGIN.EXAMPLE.COM",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match rejects parent domain for subdomain uri`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.example.com",
            targetUrl = "https://example.com/path?q=1",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match applies equivalent domains when target url has path`() = runTest {
        assertMatches(
            savedUri = "login.example.net",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Host,
        )
    }

    @Test
    fun `host match preserves subdomain prefix during equivalent domain substitution`() = runTest {
        assertDoesNotMatch(
            savedUri = "app.example.net",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.Host,
        )
    }

    @Test
    fun `host match accepts target port when saved uri has no explicit port`() = runTest {
        assertMatches(
            savedUri = "login.example.com",
            targetUrl = "https://login.example.com:8443/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match accepts saved port when target url has no explicit port`() = runTest {
        assertMatches(
            savedUri = "login.example.com:9443",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match accepts same explicit ports`() = runTest {
        assertMatches(
            savedUri = "login.example.com:8443",
            targetUrl = "https://login.example.com:8443/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match accepts explicit default port when target url has no explicit port`() = runTest {
        assertMatches(
            savedUri = "login.example.com:443",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match handles ipv4 hosts with ports`() = runTest {
        assertMatches(
            savedUri = "127.0.0.1:8080",
            targetUrl = "http://127.0.0.1:8080/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match rejects different explicit ports`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.example.com:9443",
            targetUrl = "https://login.example.com:8443/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `host match propagates tld lookup failures`() = runTest {
        assertCheckFails(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "broken.example.com",
            targetUrl = "https://broken.example.com/path",
            match = DSecret.Uri.MatchType.Host,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with match trims one trailing slash`() = runTest {
        assertMatches(
            savedUri = "https://example.com/path/",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with match applies equivalent domains when target url has path`() = runTest {
        assertMatches(
            savedUri = "https://login.example.net/path",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.StartsWith,
        )
    }

    @Test
    fun `starts with match trims whitespace before comparison`() = runTest {
        assertMatches(
            savedUri = "  https://example.com/path  ",
            targetUrl = "  https://example.com/path?q=1  ",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with match removes only one trailing slash`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://example.com/path//",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with match uses raw prefix semantics`() = runTest {
        assertMatches(
            savedUri = "https://example.com/path",
            targetUrl = "https://example.com/pathology",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with match falls back to raw comparison when tld lookup fails`() = runTest {
        assertMatches(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "https://broken.example.com/path",
            targetUrl = "https://broken.example.com/path?q=1",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `starts with fallback does not apply equivalent domains`() = runTest {
        assertDoesNotMatch(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "https://broken.example.net/path",
            targetUrl = "https://broken.example.com/path?q=1",
            match = DSecret.Uri.MatchType.StartsWith,
            equivalentDomains = defaultEquivalentDomains,
        )
    }

    @Test
    fun `starts with match rejects different paths`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://login.example.net/admin",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.StartsWith,
        )
    }

    @Test
    fun `exact match trims one trailing slash`() = runTest {
        assertMatches(
            savedUri = "https://example.com/path/",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `exact match trims whitespace before comparison`() = runTest {
        assertMatches(
            savedUri = "  https://example.com/path  ",
            targetUrl = "  https://example.com/path  ",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `exact match removes only one trailing slash`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://example.com/path//",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `exact match is case sensitive`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://LOGIN.example.com/path",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `exact match does not normalize default ports`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://example.com:443/path",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `exact match does not apply equivalent domains`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://login.example.net/path",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.Exact,
        )
    }

    @Test
    fun `regular expression match is case insensitive`() = runTest {
        assertMatches(
            savedUri = "https://login\\.example\\.com/path",
            targetUrl = "HTTPS://LOGIN.EXAMPLE.COM/PATH",
            match = DSecret.Uri.MatchType.RegularExpression,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `regular expression rejects invalid patterns`() = runTest {
        assertCheckFails(
            savedUri = "[",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.RegularExpression,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `regular expression falls back to raw target when tld lookup fails`() = runTest {
        assertMatches(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "https://broken\\.example\\.com/path\\?q=1",
            targetUrl = "https://broken.example.com/path?q=1",
            match = DSecret.Uri.MatchType.RegularExpression,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `regular expression fallback does not apply equivalent domains`() = runTest {
        assertDoesNotMatch(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "https://broken\\.example\\.net/path\\?q=1",
            targetUrl = "https://broken.example.com/path?q=1",
            match = DSecret.Uri.MatchType.RegularExpression,
            equivalentDomains = defaultEquivalentDomains,
        )
    }

    @Test
    fun `regular expression match applies equivalent domains when target url has path`() = runTest {
        assertMatches(
            savedUri = "https://login\\.example\\.net/path\\?q=1",
            targetUrl = "https://login.example.com/path?q=1",
            match = DSecret.Uri.MatchType.RegularExpression,
        )
    }

    @Test
    fun `regular expression must match whole url`() = runTest {
        assertDoesNotMatch(
            savedUri = "login\\.example\\.com",
            targetUrl = "https://login.example.com/path",
            match = DSecret.Uri.MatchType.RegularExpression,
            equivalentDomains = emptyEquivalentDomains,
        )
    }

    @Test
    fun `never match always rejects`() = runTest {
        assertDoesNotMatch(
            savedUri = "https://example.com/path",
            targetUrl = "https://example.com/path",
            match = DSecret.Uri.MatchType.Never,
        )
    }

    @Test
    fun `never match does not parse urls or call tld service`() = runTest {
        assertDoesNotMatch(
            cipherUrlCheck = CipherUrlCheckImpl(ThrowingCipherUrlTldService),
            savedUri = "[",
            targetUrl = "https://broken.example.com/path",
            match = DSecret.Uri.MatchType.Never,
        )
    }

    private suspend fun assertMatches(
        cipherUrlCheck: CipherUrlCheckImpl = this.cipherUrlCheck,
        savedUri: String,
        targetUrl: String,
        match: DSecret.Uri.MatchType?,
        defaultMatchDetection: DSecret.Uri.MatchType = DSecret.Uri.MatchType.Domain,
        equivalentDomains: EquivalentDomains = defaultEquivalentDomains,
    ) {
        val result = matches(
            savedUri = savedUri,
            targetUrl = targetUrl,
            match = match,
            defaultMatchDetection = defaultMatchDetection,
            equivalentDomains = equivalentDomains,
            cipherUrlCheck = cipherUrlCheck,
        )

        assertTrue(result)
    }

    private suspend fun assertDoesNotMatch(
        cipherUrlCheck: CipherUrlCheckImpl = this.cipherUrlCheck,
        savedUri: String,
        targetUrl: String,
        match: DSecret.Uri.MatchType?,
        defaultMatchDetection: DSecret.Uri.MatchType = DSecret.Uri.MatchType.Domain,
        equivalentDomains: EquivalentDomains = defaultEquivalentDomains,
    ) {
        val result = matches(
            savedUri = savedUri,
            targetUrl = targetUrl,
            match = match,
            defaultMatchDetection = defaultMatchDetection,
            equivalentDomains = equivalentDomains,
            cipherUrlCheck = cipherUrlCheck,
        )

        assertFalse(result)
    }

    private suspend fun assertCheckFails(
        cipherUrlCheck: CipherUrlCheckImpl = this.cipherUrlCheck,
        savedUri: String,
        targetUrl: String,
        match: DSecret.Uri.MatchType?,
        defaultMatchDetection: DSecret.Uri.MatchType = DSecret.Uri.MatchType.Domain,
        equivalentDomains: EquivalentDomains = defaultEquivalentDomains,
    ) {
        try {
            matches(
                savedUri = savedUri,
                targetUrl = targetUrl,
                match = match,
                defaultMatchDetection = defaultMatchDetection,
                equivalentDomains = equivalentDomains,
                cipherUrlCheck = cipherUrlCheck,
            )
        } catch (e: Throwable) {
            return
        }

        fail("Expected URL check to fail.")
    }

    private suspend fun matches(
        savedUri: String,
        targetUrl: String,
        match: DSecret.Uri.MatchType?,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
        cipherUrlCheck: CipherUrlCheckImpl = this.cipherUrlCheck,
    ): Boolean = cipherUrlCheck(
        uri = DSecret.Uri(
            uri = savedUri,
            match = match,
        ),
        url = targetUrl,
        defaultMatchDetection = defaultMatchDetection,
        equivalentDomains = equivalentDomains,
    ).bind()

    private companion object {
        val emptyEquivalentDomains = EquivalentDomains(
            domains = emptyMap(),
        )

        val defaultEquivalentDomains = EquivalentDomains(
            domains = mapOf(
                "example.com" to listOf("example.com", "example.net"),
                "example.net" to listOf("example.com", "example.net"),
            ),
        )

        val oneWayEquivalentDomains = EquivalentDomains(
            domains = mapOf(
                "example.com" to listOf("example.com", "example.net"),
            ),
        )
    }
}

private object CipherUrlHostOnlyTldService : TldService {
    override val version: String = "test"

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        val normalizedHost = host.removeSuffix(".").lowercase()
        when {
            normalizedHost.endsWith(".example.com") || normalizedHost == "example.com" -> "example.com"
            normalizedHost.endsWith(".example.net") || normalizedHost == "example.net" -> "example.net"
            normalizedHost.endsWith(".example.co.uk") || normalizedHost == "example.co.uk" -> "example.co.uk"
            normalizedHost.endsWith(".unrelated.org") || normalizedHost == "unrelated.org" -> "unrelated.org"
            else -> normalizedHost
        }
    }
}

private object ThrowingCipherUrlTldService : TldService {
    override val version: String = "test"

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        error("Broken test host.")
    }
}
