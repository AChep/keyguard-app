package com.artemchep.keyguard.feature.browserextension.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BrowserMessagesTest {
    @Test
    fun `a request is read with its id and type and fields`() {
        val text = """{"id":1,"type":"hello","protocol":1,"capabilities":["passkeys","save"],"later":{"field":true}}"""
        val request = BrowserMessages.parseRequest(text.encodeToByteArray())
        assertEquals(1L, request.id)
        assertEquals("hello", request.type)
        // Unknown fields are ignored.
        assertEquals(
            HelloRequest(protocol = 1, capabilities = listOf("passkeys", "save")),
            request.decode<HelloRequest>(),
        )
    }

    @Test
    fun `a message that nests too deep ends the session`() {
        // 100k nested arrays overflow the parser's stack: refused before parsing.
        val deep = """{"id":1,"type":"hello","later":${"[".repeat(100_000)}${"]".repeat(100_000)}}"""
        assertFailsWith<BrowserProtocolException> {
            BrowserMessages.parseRequest(deep.encodeToByteArray())
        }
        // Brackets in a string don't count.
        val open = """{"a":""".repeat(BrowserProtocol.MAX_DEPTH - 1)
        val close = "}".repeat(BrowserProtocol.MAX_DEPTH - 1)
        val text = """{"id":1,"type":"hello","s":"[[[[\"[[","later":${open}1$close}"""
        assertEquals(1L, BrowserMessages.parseRequest(text.encodeToByteArray()).id)
    }

    @Test
    fun `the largest id is 2 to the 53 minus 1`() {
        assertEquals(BrowserProtocol.MAX_ID, parse("""{"id":9007199254740991,"type":"unlock"}""").id)
    }

    @Test
    fun `a request without a valid id ends the session`() {
        for (text in listOf(
            """{"type":"unlock"}""",
            """{"id":"1","type":"unlock"}""",
            """{"id":0,"type":"unlock"}""",
            """{"id":-1,"type":"unlock"}""",
            """{"id":1.5,"type":"unlock"}""",
            """{"id":null,"type":"unlock"}""",
            """{"id":9007199254740992,"type":"unlock"}""",
        )) {
            assertFailsWith<BrowserProtocolException>(text) { parse(text) }
        }
    }

    @Test
    fun `a message that is not a UTF-8 JSON object ends the session`() {
        for (text in listOf("""[{"id":1}]""", "1", "\"text\"", """{"id":1""", "")) {
            assertFailsWith<BrowserProtocolException>(text) { parse(text) }
        }
        // A lone continuation byte.
        val invalid = byteArrayOf('{'.code.toByte(), 0x80.toByte(), '}'.code.toByte())
        assertFailsWith<BrowserProtocolException> { BrowserMessages.parseRequest(invalid) }
    }

    @Test
    fun `a request without a string type has none`() {
        assertNull(parse("""{"id":1}""").type)
        assertNull(parse("""{"id":1,"type":7}""").type)
    }

    @Test
    fun `a missing or mistyped field is an invalid request`() {
        for (text in listOf(
            """{"id":2,"type":"pair_commit","commit":"AAAA"}""",
            """{"id":2,"type":"pair_commit","commit":7,"name":"Chrome"}""",
            """{"id":2,"type":"pair_commit","commit":null,"name":"Chrome"}""",
        )) {
            val error = assertFailsWith<BrowserRequestException>(text) { parse(text).decode<PairCommitRequest>() }
            assertEquals(BrowserError.INVALID_REQUEST, error.code)
        }
    }

    // The texts of `browserExtension/protocol/vectors/noise_xx.json`.
    @Test
    fun `responses match the shared vectors`() {
        val hello = HelloResponse(
            protocol = 1,
            capabilities = listOf("passkeys", "save"),
            paired = false,
            vault = BrowserVaultState.LOCKED,
        )
        assertEquals(
            """{"id":1,"ok":true,"protocol":1,"capabilities":["passkeys","save"],"paired":false,"vault":"locked"}""",
            BrowserMessages.response(1, browserOk(hello)).decodeToString(),
        )
        assertEquals(
            """{"id":2,"ok":true,"nonce":"oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8="}""",
            BrowserMessages.response(2, browserOk(PairCommitResponse("oKGio6SlpqeoqaqrrK2ur7CxsrO0tba3uLm6u7y9vr8=")))
                .decodeToString(),
        )
    }

    @Test
    fun `errors and events have their shapes`() {
        assertEquals(
            """{"id":3,"ok":false,"error":"vault_locked"}""",
            BrowserMessages.response(3, BrowserReply.Error(BrowserError.VAULT_LOCKED)).decodeToString(),
        )
        assertEquals(
            """{"event":"vault","state":"unlocked"}""",
            BrowserMessages.event(BrowserEvent.VAULT, browserOk(VaultEvent(BrowserVaultState.UNLOCKED)).fields)
                .decodeToString(),
        )
        assertEquals("""{"event":"revoked"}""", BrowserMessages.event(BrowserEvent.REVOKED).decodeToString())
    }

    @Test
    fun `a response larger than 1 MiB becomes too_large`() {
        val password = "a".repeat(BrowserFragments.MAX_MESSAGE_SIZE)
        assertEquals(
            """{"id":4,"ok":false,"error":"too_large"}""",
            BrowserMessages.response(4, browserOk(GenerateResponse(password))).decodeToString(),
        )
    }

    @Test
    fun `optional fields are omitted when absent`() {
        assertEquals(
            """{"id":5,"ok":true,"username":"user"}""",
            BrowserMessages.response(5, browserOk(FillResponse(username = "user"))).decodeToString(),
        )
    }

    @Test
    fun `fill and totp read the top origin when present`() {
        val framed = """{"id":6,"type":"fill","handle":"h","url":"https://login.example.com/",""" +
            """"top_origin":"https://example.net"}"""
        assertEquals(
            HandleRequest(handle = "h", url = "https://login.example.com/", topOrigin = "https://example.net"),
            parse(framed).decode<HandleRequest>(),
        )
        val top = """{"id":7,"type":"totp","handle":"h","url":"https://login.example.com/"}"""
        assertEquals(
            HandleRequest(handle = "h", url = "https://login.example.com/"),
            parse(top).decode<HandleRequest>(),
        )
        val mistyped = """{"id":8,"type":"fill","handle":"h","url":"https://login.example.com/",""" +
            """"top_origin":7}"""
        val error = assertFailsWith<BrowserRequestException> { parse(mistyped).decode<HandleRequest>() }
        assertEquals(BrowserError.INVALID_REQUEST, error.code)
    }

    private fun parse(text: String) = BrowserMessages.parseRequest(text.encodeToByteArray())
}
