package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.exception.WordlistWebPageException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReadWordlistFromUrlImplTest {
    @Test
    fun `imports words from a successful response`() = runTest {
        val client = HttpClient(MockEngine {
            respond("# comment\nalpha\n\nbravo\ncharlie\n", HttpStatusCode.OK)
        })
        try {
            val words = ReadWordlistFromUrlImpl(client)("https://example.com/words.txt").bind()

            assertEquals(listOf("alpha", "bravo", "charlie"), words)
        } finally {
            client.close()
        }
    }

    @Test
    fun `rejects error pages instead of importing their lines`() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            val client = HttpClient(MockEngine {
                respond("<html>\n<h1>Error response</h1>\n</html>", status)
            })
            try {
                val result = ReadWordlistFromUrlImpl(client)("https://example.com/missing.txt")
                    .attempt().bind()

                val error = assertIs<HttpException>(result.leftOrNull())
                assertEquals(status, error.statusCode)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun `rejects a web page served with a success status`() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                "<!DOCTYPE html>\n<html>\n<body>alpha</body>\n</html>",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
            )
        })
        try {
            val result = ReadWordlistFromUrlImpl(client)("https://example.com/blob/main/words.txt")
                .attempt().bind()

            assertIs<WordlistWebPageException>(result.leftOrNull())
        } finally {
            client.close()
        }
    }

    @Test
    fun `imports words from a plain text response`() = runTest {
        val client = HttpClient(MockEngine {
            respond(
                "alpha\nbravo\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/plain; charset=utf-8"),
            )
        })
        try {
            val words = ReadWordlistFromUrlImpl(client)("https://example.com/raw/main/words.txt").bind()

            assertEquals(listOf("alpha", "bravo"), words)
        } finally {
            client.close()
        }
    }

    @Test
    fun `follows a redirect to a wordlist`() = runTest {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/redirect") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "/words.txt"))
            } else {
                respond("alpha\nbravo\n", HttpStatusCode.OK)
            }
        })
        try {
            val words = ReadWordlistFromUrlImpl(client)("https://example.com/redirect").bind()

            assertEquals(listOf("alpha", "bravo"), words)
        } finally {
            client.close()
        }
    }
}
