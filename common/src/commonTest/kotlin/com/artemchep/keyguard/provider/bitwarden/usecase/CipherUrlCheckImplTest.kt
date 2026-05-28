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

class CipherUrlCheckImplTest {
    private val cipherUrlCheck = CipherUrlCheckImpl(CipherUrlHostOnlyTldService)

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
    fun `host match compares exact host ignoring case`() = runTest {
        assertMatches(
            savedUri = "LOGIN.EXAMPLE.COM",
            targetUrl = "https://login.example.com/path?q=1",
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
    fun `host match rejects different explicit ports`() = runTest {
        assertDoesNotMatch(
            savedUri = "login.example.com:9443",
            targetUrl = "https://login.example.com:8443/path",
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

    private suspend fun assertMatches(
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
        )

        assertTrue(result)
    }

    private suspend fun assertDoesNotMatch(
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
        )

        assertFalse(result)
    }

    private suspend fun matches(
        savedUri: String,
        targetUrl: String,
        match: DSecret.Uri.MatchType?,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
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
    }
}

private object CipherUrlHostOnlyTldService : TldService {
    override val version: String = "test"

    override fun getDomainName(
        host: String,
    ): IO<String> = {
        when {
            host.endsWith(".example.com") || host == "example.com" -> "example.com"
            host.endsWith(".example.net") || host == "example.net" -> "example.net"
            host.endsWith(".unrelated.org") || host == "unrelated.org" -> "unrelated.org"
            else -> host.lowercase()
        }
    }
}
