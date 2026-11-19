package com.artemchep.keyguard.common.service.browser

import com.artemchep.keyguard.common.util.hexToByteArray
import com.artemchep.keyguard.crypto.NativeCryptoGenerator
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserMessages
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserReassembler
import com.artemchep.keyguard.feature.browserextension.protocol.BrowserVaultState
import com.artemchep.keyguard.feature.browserextension.protocol.HelloRequest
import com.artemchep.keyguard.feature.browserextension.protocol.HelloResponse
import com.artemchep.keyguard.feature.browserextension.protocol.PairCommitRequest
import com.artemchep.keyguard.feature.browserextension.protocol.PairCommitResponse
import com.artemchep.keyguard.feature.browserextension.protocol.browserOk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app against `browserExtension/protocol/vectors/noise_xx.json`, which the extension reads too. */
class BrowserProtocolVectorsTest {
    private val vectors = run {
        val text = requireNotNull(javaClass.getResource("/noise_xx.json")) { "Missing noise_xx.json" }.readText()
        Json.parseToJsonElement(text).jsonObject.getValue("keyguard").jsonObject
    }
    private val pairing = vectors.getValue("pairing").jsonObject
    private val crypto = NativeCryptoGenerator()

    @Test
    fun `the commitment and the code match the vectors`() {
        val extensionNonce = pairing.hex("extension_nonce")
        assertContentEquals(pairing.hex("commit"), BrowserPairingCode.commit(extensionNonce, crypto))
        assertTrue(BrowserPairingCode.matches(pairing.hex("commit"), extensionNonce, crypto))

        val code = BrowserPairingCode.code(
            handshakeHash = vectors.hex("handshake_hash"),
            appStatic = vectors.hex("resp_static_public"),
            extensionStatic = vectors.hex("init_static_public"),
            appNonce = pairing.hex("app_nonce"),
            extensionNonce = extensionNonce,
            crypto = crypto,
        )
        assertEquals(pairing.getValue("code").jsonPrimitive.content, code)
        // Either order of the statics gives the same code: they are sorted.
        assertEquals(
            code,
            BrowserPairingCode.code(
                handshakeHash = vectors.hex("handshake_hash"),
                appStatic = vectors.hex("init_static_public"),
                extensionStatic = vectors.hex("resp_static_public"),
                appNonce = pairing.hex("app_nonce"),
                extensionNonce = extensionNonce,
                crypto = crypto,
            ),
        )
    }

    @Test
    fun `the framed messages read and write as in the vectors`() {
        val framed = vectors.getValue("messages").jsonArray
            .map { it.jsonObject }
            .filter { "text" in it }
        // `hello`, its response, `pair_commit`, its response.
        assertEquals(4, framed.size)
        val hello = framed[0]
        val helloResponse = framed[1]
        val commit = framed[2]
        val commitResponse = framed[3]
        // Each message is one fragment: the flags byte, then the JSON.
        for (message in framed) {
            val payload = message.hex("payload")
            assertEquals(0, payload[0].toInt())
            assertEquals(message.text(), BrowserReassembler().add(payload)?.decodeToString())
        }

        val helloRequest = BrowserMessages.parseRequest(hello.text().encodeToByteArray())
        assertEquals(1, helloRequest.decode<HelloRequest>().protocol)
        val pairCommit = BrowserMessages.parseRequest(commit.text().encodeToByteArray()).decode<PairCommitRequest>()
        assertContentEquals(pairing.hex("commit"), Base64.decode(pairCommit.commit))

        // The app pairs only while its vault is unlocked.
        val helloReply = HelloResponse(
            protocol = 1,
            capabilities = listOf("passkeys", "save"),
            paired = false,
            vault = BrowserVaultState.UNLOCKED,
        )
        assertEquals(helloResponse.text(), BrowserMessages.response(1, browserOk(helloReply)).decodeToString())
        val nonce = Base64.encode(pairing.hex("app_nonce"))
        assertEquals(
            commitResponse.text(),
            BrowserMessages.response(2, browserOk(PairCommitResponse(nonce))).decodeToString(),
        )
    }

    private fun JsonObject.hex(key: String): ByteArray = getValue(key).jsonPrimitive.content.hexToByteArray()

    private fun JsonObject.text(): String = getValue("text").jsonPrimitive.content
}
