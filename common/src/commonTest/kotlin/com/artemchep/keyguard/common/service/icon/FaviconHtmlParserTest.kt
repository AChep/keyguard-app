package com.artemchep.keyguard.common.service.icon

import io.ktor.http.Url
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FaviconHtmlParserTest {
    private val pageUrl = Url("https://example.com/")

    @Test
    fun `collects icons from the head`() {
        val page = parseFaviconPage(
            html = """
                <html><head>
                  <link rel="icon" href="/favicon-32.png" sizes="32x32">
                  <LINK REL="Shortcut Icon" HREF="/favicon.ico">
                  <link rel="apple-touch-icon" href="/touch.png">
                  <link rel="apple-touch-icon-precomposed" href="/touch-pre.png" sizes="152x152">
                  <link rel="mask-icon" href="/mask.svg">
                  <link rel="stylesheet" href="/style.css">
                </head><body></body></html>
            """.trimIndent(),
            pageUrl = pageUrl,
        )

        assertEquals(
            listOf(
                FaviconCandidate(Url("https://example.com/favicon-32.png"), 32, FaviconCandidate.Type.ICON),
                FaviconCandidate(Url("https://example.com/favicon.ico"), null, FaviconCandidate.Type.ICON),
                FaviconCandidate(Url("https://example.com/touch.png"), null, FaviconCandidate.Type.APPLE_TOUCH_ICON),
                FaviconCandidate(Url("https://example.com/touch-pre.png"), 152, FaviconCandidate.Type.APPLE_TOUCH_ICON),
            ),
            page.candidates,
        )
    }

    @Test
    fun `ignores links in the body`() {
        val page = parseFaviconPage(
            html = """
                <html><head><title>x</title></head>
                <body><link rel="icon" href="/body.png"></body></html>
            """.trimIndent(),
            pageUrl = pageUrl,
        )

        assertEquals(emptyList(), page.candidates)
    }

    @Test
    fun `resolves relative links against the page`() {
        val page = parseFaviconPage(
            html = """
                <link rel="icon" href="icon.png">
                <link rel="icon" href="//cdn.example.net/icon.png">
                <link rel="icon" href="https://static.example.org/icon.png">
                <link rel="icon" href="../up.png">
            """.trimIndent(),
            pageUrl = Url("https://example.com:8443/dir/page.html?lang=en#top"),
        )

        assertEquals(
            listOf(
                "https://example.com:8443/dir/icon.png",
                "https://cdn.example.net/icon.png",
                "https://static.example.org/icon.png",
            ),
            page.candidates.take(3).map { it.url.toString() },
        )
        assertEquals(4, page.candidates.size)
    }

    @Test
    fun `resolves relative links against the base element`() {
        val page = parseFaviconPage(
            html = """
                <head>
                  <base href="https://static.example.com/assets/">
                  <link rel="icon" href="icon.png">
                </head>
            """.trimIndent(),
            pageUrl = pageUrl,
        )

        assertEquals(
            listOf(Url("https://static.example.com/assets/icon.png")),
            page.candidates.map { it.url },
        )
    }

    @Test
    fun `skips svg, data and non-http links`() {
        val page = parseFaviconPage(
            html = """
                <link rel="icon" type="image/svg+xml" href="/icon.svg">
                <link rel="icon" href="data:image/png;base64,AAAA">
                <link rel="icon" href="javascript:alert(1)">
                <link rel="icon" href="">
                <link rel="icon" href="/ok.png">
            """.trimIndent(),
            pageUrl = pageUrl,
        )

        assertEquals(
            listOf(Url("https://example.com/ok.png")),
            page.candidates.map { it.url },
        )
    }

    @Test
    fun `finds the manifest`() {
        val page = parseFaviconPage(
            html = """<link rel="manifest" href="/site.webmanifest">""",
            pageUrl = pageUrl,
        )

        assertEquals(Url("https://example.com/site.webmanifest"), page.manifestUrl)
        assertEquals(emptyList(), page.candidates)
    }

    @Test
    fun `parses manifest icons`() {
        val candidates = parseFaviconManifest(
            json = Json,
            text = """
                {
                  "icons": [
                    { "src": "icon-192.png", "sizes": "192x192", "type": "image/png" },
                    { "src": "icon.svg", "sizes": "any", "type": "image/svg+xml" },
                    { "src": "mono.png", "sizes": "512x512", "purpose": "monochrome" },
                    { "src": "maskable.png", "sizes": "512x512", "purpose": "maskable any" },
                    { "sizes": "48x48" }
                  ]
                }
            """.trimIndent(),
            manifestUrl = Url("https://example.com/app/manifest.json"),
        )

        assertEquals(
            listOf(
                FaviconCandidate(Url("https://example.com/app/icon-192.png"), 192, FaviconCandidate.Type.MANIFEST),
                FaviconCandidate(Url("https://example.com/app/maskable.png"), 512, FaviconCandidate.Type.MANIFEST),
            ),
            candidates,
        )
    }

    @Test
    fun `garbage manifest has no icons`() {
        assertEquals(emptyList(), parseFaviconManifest(Json, "not json", pageUrl))
        assertEquals(emptyList(), parseFaviconManifest(Json, "[]", pageUrl))
    }

    @Test
    fun `parses icon sizes`() {
        assertEquals(32, parseIconSize("32x32"))
        assertEquals(64, parseIconSize("16x16 32x32 64X48"))
        assertNull(parseIconSize("any"))
        assertNull(parseIconSize("garbage"))
        assertNull(parseIconSize(null))
    }

    @Test
    fun `picks the largest icon`() {
        val picked = pickFavicon(
            candidates = listOf(
                FaviconCandidate(Url("https://example.com/favicon.ico"), null, FaviconCandidate.Type.ICON),
                FaviconCandidate(Url("https://example.com/touch.png"), null, FaviconCandidate.Type.APPLE_TOUCH_ICON),
                FaviconCandidate(Url("https://example.com/icon-96.png"), 96, FaviconCandidate.Type.ICON),
            ),
            origin = pageUrl,
            siteIsLocal = false,
        )

        assertEquals(Url("https://example.com/touch.png"), picked)
    }

    @Test
    fun `prefers png over ico of the same size`() {
        val picked = pickFavicon(
            candidates = listOf(
                FaviconCandidate(Url("https://example.com/favicon.ico"), 32, FaviconCandidate.Type.ICON),
                FaviconCandidate(Url("https://example.com/favicon.png"), 32, FaviconCandidate.Type.ICON),
            ),
            origin = pageUrl,
            siteIsLocal = false,
        )

        assertEquals(Url("https://example.com/favicon.png"), picked)
    }

    @Test
    fun `falls back to the conventional icon`() {
        val picked = pickFavicon(
            candidates = listOf(
                FaviconCandidate(Url("https://example.com/icon.svg"), 512, FaviconCandidate.Type.ICON),
            ),
            origin = Url("https://example.com:8443"),
            siteIsLocal = false,
        )

        assertEquals(Url("https://example.com:8443/favicon.ico"), picked)
    }

    @Test
    fun `a public website can not point at a local address`() {
        val candidates = listOf(
            FaviconCandidate(Url("http://192.168.1.1/icon.png"), 512, FaviconCandidate.Type.ICON),
        )

        assertEquals(
            Url("https://example.com/favicon.ico"),
            pickFavicon(candidates, origin = pageUrl, siteIsLocal = false),
        )
        assertEquals(
            Url("http://192.168.1.1/icon.png"),
            pickFavicon(candidates, origin = Url("http://192.168.1.1"), siteIsLocal = true),
        )
    }
}
