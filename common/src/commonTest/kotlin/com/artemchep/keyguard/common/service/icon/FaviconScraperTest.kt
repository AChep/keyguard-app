package com.artemchep.keyguard.common.service.icon

import com.artemchep.keyguard.common.service.http.configureKeyguardIconHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FaviconScraperTest {
    private val origin = Url("https://example.com")

    @Test
    fun `reads the home page of the website`() = runTest {
        val server = FakeServer { request ->
            when (request.url.encodedPath) {
                "/" -> html("""<link rel="apple-touch-icon" href="/touch.png">""")
                else -> notFound()
            }
        }

        val icon = server.scraper().find(origin)

        assertEquals(Url("https://example.com/touch.png"), icon)
        assertEquals(listOf("https://example.com/"), server.requests)
    }

    @Test
    fun `resolves links against the redirected page`() = runTest {
        val server = FakeServer { request ->
            when (request.url.toString()) {
                "https://example.com/" -> redirect("https://www.example.com/en/")
                "https://www.example.com/en/" -> html("""<link rel="icon" href="icon.png" sizes="64x64">""")
                else -> notFound()
            }
        }

        val icon = server.scraper().find(origin)

        assertEquals(Url("https://www.example.com/en/icon.png"), icon)
    }

    @Test
    fun `does not follow a redirect to a local address`() = runTest {
        val server = FakeServer { request ->
            when (request.url.toString()) {
                "https://example.com/" -> redirect("http://192.168.1.1/")
                else -> html("""<link rel="icon" href="/router.png" sizes="64x64">""")
            }
        }

        val icon = server.scraper().find(origin)

        assertEquals(Url("https://example.com/favicon.ico"), icon)
        assertEquals(listOf("https://example.com/"), server.requests)
    }

    @Test
    fun `a local website may redirect to a local address`() = runTest {
        val server = FakeServer { request ->
            when (request.url.toString()) {
                "http://192.168.1.1/" -> redirect("http://192.168.1.2/")
                "http://192.168.1.2/" -> html("""<link rel="icon" href="/nas.png" sizes="64x64">""")
                else -> notFound()
            }
        }

        val icon = server.scraper().find(Url("http://192.168.1.1"))

        assertEquals(Url("http://192.168.1.2/nas.png"), icon)
    }

    @Test
    fun `falls back to the conventional icon`() = runTest {
        val server = FakeServer { html("<html><head></head></html>") }

        assertEquals(Url("https://example.com/favicon.ico"), server.scraper().find(origin))
    }

    @Test
    fun `falls back to the conventional icon if the page is not readable`() = runTest {
        val server = FakeServer { respond("", HttpStatusCode.ServiceUnavailable) }

        assertEquals(Url("https://example.com/favicon.ico"), server.scraper().find(origin))
        // The icon client does not retry.
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `reads the start of a large page`() = runTest {
        val server = FakeServer {
            html("""<html><head><link rel="icon" href="/x.png" sizes="64x64"></head><body>""" + " ".repeat(300 * 1024))
        }

        assertEquals(Url("https://example.com/x.png"), server.scraper().find(origin))
    }

    @Test
    fun `falls back to the conventional icon if the page is too large`() = runTest {
        val server = FakeServer {
            html("<html><head>" + " ".repeat(300 * 1024) + """<link rel="icon" href="/x.png"></head>""")
        }

        assertEquals(Url("https://example.com/favicon.ico"), server.scraper().find(origin))
    }

    @Test
    fun `reads the manifest only without a large icon`() = runTest {
        val manifest = """{ "icons": [ { "src": "/512.png", "sizes": "512x512" } ] }"""
        val small = FakeServer { request ->
            when (request.url.encodedPath) {
                "/" -> html("""<link rel="manifest" href="/m.json"><link rel="icon" href="/32.png">""")
                "/m.json" -> respond(manifest, HttpStatusCode.OK)
                else -> notFound()
            }
        }
        val large = FakeServer { request ->
            when (request.url.encodedPath) {
                "/" -> html("""<link rel="manifest" href="/m.json"><link rel="apple-touch-icon" href="/t.png">""")
                else -> notFound()
            }
        }

        assertEquals(Url("https://example.com/512.png"), small.scraper().find(origin))
        assertEquals(Url("https://example.com/t.png"), large.scraper().find(origin))
        assertEquals(listOf("https://example.com/"), large.requests)
    }

    @Test
    fun `remembers the icon of a website`() = runTest {
        val server = FakeServer { html("""<link rel="icon" href="/x.png">""") }
        val scraper = server.scraper()

        scraper.find(origin)
        scraper.find(origin)

        assertEquals(1, server.requests.size)
    }

    @Test
    fun `remembers a failure for a while`() = runTest {
        val clock = FakeClock()
        val server = FakeServer { throw IOException("unreachable") }
        val scraper = server.scraper(clock)

        assertNull(scraper.find(origin))
        clock.now += 5.minutes
        assertNull(scraper.find(origin))
        assertEquals(1, server.requests.size)

        clock.now += 6.minutes
        assertNull(scraper.find(origin))
        assertEquals(2, server.requests.size)
    }

    @Test
    fun `forgets the least recently used website`() = runTest {
        val server = FakeServer { html("") }
        val scraper = server.scraper(capacity = 2)

        scraper.find(Url("https://a.example.com"))
        scraper.find(Url("https://b.example.com"))
        scraper.find(Url("https://a.example.com"))
        scraper.find(Url("https://c.example.com"))
        scraper.find(Url("https://a.example.com"))
        scraper.find(Url("https://b.example.com"))

        assertEquals(
            listOf(
                "https://a.example.com/",
                "https://b.example.com/",
                "https://c.example.com/",
                "https://b.example.com/",
            ),
            server.requests,
        )
    }

    private class FakeClock : Clock {
        var now = Instant.fromEpochSeconds(1_700_000_000)

        override fun now(): Instant = now
    }

    private class FakeServer(
        private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) {
        val requests = mutableListOf<String>()

        fun scraper(
            clock: Clock = FakeClock(),
            capacity: Int = 512,
        ): FaviconScraper {
            val engine = MockEngine { request ->
                requests += request.url.toString()
                handler(request)
            }
            return FaviconScraper(
                httpClient = HttpClient(engine) {
                    configureKeyguardIconHttpClient()
                },
                json = Json,
                clock = clock,
                capacity = capacity,
            )
        }
    }
}

private fun MockRequestHandleScope.html(body: String) = respond(
    content = body,
    status = HttpStatusCode.OK,
    headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
)

private fun MockRequestHandleScope.redirect(location: String) = respond(
    content = "",
    status = HttpStatusCode.MovedPermanently,
    headers = headersOf(HttpHeaders.Location, location),
)

private fun MockRequestHandleScope.notFound() = respond("", HttpStatusCode.NotFound)
