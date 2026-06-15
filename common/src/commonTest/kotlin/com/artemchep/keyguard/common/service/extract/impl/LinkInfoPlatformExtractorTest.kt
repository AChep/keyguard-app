package com.artemchep.keyguard.common.service.extract.impl

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinkInfoPlatformExtractorTest {
    private val extractor = LinkInfoPlatformExtractor()

    @Test
    fun `handles always returns true`() {
        assertTrue(
            extractor.handles(
                DSecret.Uri(uri = "anything"),
            ),
        )
    }

    @Test
    fun `android fast path is case insensitive`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "AnDrOiDaPp://com.example.app"),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.Android>(result)

        assertEquals("com.example.app", platform.packageName)
    }

    @Test
    fun `ios fast path is case insensitive`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "IoSaPp://com.example.app"),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.IOS>(result)

        assertEquals("com.example.app", platform.bundleId)
    }

    @Test
    fun `explicit http stays unchanged`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "http://google.com/path"),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.Web>(result)

        assertEquals("http://google.com/path", platform.url.toString())
        assertEquals("http://google.com", platform.frontPageUrl.toString())
    }

    @Test
    fun `explicit https stays unchanged and front page strips path query and fragment`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "https://google.com/path?q=1#frag"),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.Web>(result)

        assertEquals("https://google.com/path?q=1#frag", platform.url.toString())
        assertEquals("https://google.com", platform.frontPageUrl.toString())
    }

    @Test
    fun `bare domain defaults to https`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "google.com"),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.Web>(result)

        assertEquals("https", platform.url.protocol.name)
        assertEquals("google.com", platform.url.host)
        assertEquals("https://google.com", platform.url.toString())
        assertEquals("https://google.com", platform.frontPageUrl.toString())
    }

    @Test
    fun `schemeless input is trimmed before https normalization`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "   google.com/path?q=1#frag   "),
        ).bind()
        val platform = assertIs<LinkInfoPlatform.Web>(result)

        assertEquals("https://google.com/path?q=1#frag", platform.url.toString())
        assertEquals("https://google.com", platform.frontPageUrl.toString())
    }

    @Test
    fun `regular expression match type always produces other`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(
                uri = "google.com",
                match = DSecret.Uri.MatchType.RegularExpression,
            ),
        ).bind()

        assertEquals(LinkInfoPlatform.Other, result)
    }

    @Test
    fun `non regex match types still use the string classifier`() = runTest {
        val matchTypes = listOf(
            null,
            DSecret.Uri.MatchType.Domain,
            DSecret.Uri.MatchType.Host,
            DSecret.Uri.MatchType.StartsWith,
            DSecret.Uri.MatchType.Exact,
            DSecret.Uri.MatchType.Never,
        )

        matchTypes.forEach { matchType ->
            val result = extractor.extractInfo(
                DSecret.Uri(
                    uri = "google.com",
                    match = matchType,
                ),
            ).bind()
            val platform = assertIs<LinkInfoPlatform.Web>(result)

            assertEquals("https://google.com", platform.url.toString())
        }
    }

    @Test
    fun `classifier url cases are parsed as web links`() = runTest {
        data class Case(
            val input: String,
            val expectedUrl: String,
            val expectedFrontPageUrl: String,
        )

        val cases = listOf(
            Case(
                input = "chrome://settings",
                expectedUrl = "chrome://settings",
                expectedFrontPageUrl = "chrome://settings",
            ),
            Case(
                input = "mailto:test@example.com",
                expectedUrl = "mailto:test@example.com",
                expectedFrontPageUrl = "mailto:st@example.com",
            ),
            Case(
                input = "127.0.0.1",
                expectedUrl = "https://127.0.0.1",
                expectedFrontPageUrl = "https://127.0.0.1",
            ),
            Case(
                input = "[::1]:8443/path",
                expectedUrl = "https://[::1]:8443/path",
                expectedFrontPageUrl = "https://[::1]:8443",
            ),
            Case(
                input = "localhost",
                expectedUrl = "https://localhost",
                expectedFrontPageUrl = "https://localhost",
            ),
            Case(
                input = "localhost:8080/path",
                expectedUrl = "localhost://localhost/8080/path",
                expectedFrontPageUrl = "localhost://localhost",
            ),
            Case(
                input = "example.com:8080/path",
                expectedUrl = "example.com://localhost/8080/path",
                expectedFrontPageUrl = "example.com://localhost",
            ),
            Case(
                input = "example.co.uk/path",
                expectedUrl = "https://example.co.uk/path",
                expectedFrontPageUrl = "https://example.co.uk",
            ),
            Case(
                input = "devserver/path?x=1",
                expectedUrl = "https://devserver/path?x=1",
                expectedFrontPageUrl = "https://devserver",
            ),
        )

        cases.forEach { case ->
            val result = extractor.extractInfo(
                DSecret.Uri(uri = case.input),
            ).bind()
            val platform = assertIs<LinkInfoPlatform.Web>(result, case.input)

            assertEquals(case.expectedUrl, platform.url.toString(), case.input)
            assertEquals(case.expectedFrontPageUrl, platform.frontPageUrl.toString(), case.input)
        }
    }

    @Test
    fun `classifier search cases stay other`() = runTest {
        val inputs = listOf(
            "",
            "   ",
            "?q=hello",
            "hello world",
            "example",
            "/relative/path",
        )

        inputs.forEach { input ->
            val result = extractor.extractInfo(
                DSecret.Uri(uri = input),
            ).bind()

            assertEquals(LinkInfoPlatform.Other, result, input)
        }
    }

    @Test
    fun `parse failures in url-like inputs return other`() = runTest {
        val result = extractor.extractInfo(
            DSecret.Uri(uri = "[::1]:70000"),
        ).bind()

        assertEquals(LinkInfoPlatform.Other, result)
    }
}
