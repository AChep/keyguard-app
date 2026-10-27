package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentFixtures
import com.artemchep.keyguard.util.foundation.crypto.hkdfSha256
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Checks the Termux bridge's transport against the vectors in
 * `commonAgent/android-bridge/tests/fixtures/secure_transport.hex`, which the
 * helpers' Rust transport checks as well.
 */
class SshAgentTcpProtocolVectorsTest {
    @Test
    fun `keys derive as in the shared vectors`() {
        val vectors = AgentFixtures.read("secure_transport.hex")
        for (direction in listOf("tool-to-app", "app-to-tool")) {
            val name = direction.replace('-', '_')
            for ((suffix, length) in listOf("key" to 32, "nonce" to 4)) {
                val derived = hkdfSha256(
                    seed = vectors.getValue("session_secret"),
                    salt = vectors.getValue("session_id"),
                    info = "keyguard-android-ssh-agent:$direction:$suffix".encodeToByteArray(),
                    length = length,
                )
                val fixture = if (suffix == "key") "${name}_key" else "${name}_nonce_prefix"
                assertEquals(vectors.getValue(fixture).toHexString(), derived.toHexString(), fixture)
            }
        }
    }

    @Test
    fun `app writes and reads the shared vectors`() {
        val vectors = AgentFixtures.read("secure_transport.hex")
        val output = ByteArrayOutputStream()
        val channel = SshAgentTcpProtocol.openAsApp(
            input = ByteArrayInputStream(vectors.concat("tool_hello", "tool_packet_1", "tool_packet_2")),
            output = output,
            sessionId = vectors.getValue("session_id"),
            sessionSecret = vectors.getValue("session_secret"),
            randomBytes = { vectors.getValue("app_challenge") },
        )
        assertContentEquals(vectors.getValue("tool_packet_1_payload"), channel.readPacket())
        assertContentEquals(vectors.getValue("tool_packet_2_payload"), channel.readPacket())
        channel.writePacket(vectors.getValue("app_packet_1_payload"))

        assertEquals(
            vectors.concat("app_hello", "app_packet_1").toHexString(),
            output.toByteArray().toHexString(),
        )
    }

    @Test
    fun `tool writes and reads the shared vectors`() {
        val vectors = AgentFixtures.read("secure_transport.hex")
        val output = ByteArrayOutputStream()
        val channel = SshAgentTcpProtocol.openAsTool(
            input = ByteArrayInputStream(vectors.concat("app_hello", "app_packet_1")),
            output = output,
            sessionId = vectors.getValue("session_id"),
            sessionSecret = vectors.getValue("session_secret"),
            randomBytes = { vectors.getValue("tool_challenge") },
        )
        channel.writePacket(vectors.getValue("tool_packet_1_payload"))
        channel.writePacket(vectors.getValue("tool_packet_2_payload"))
        assertContentEquals(vectors.getValue("app_packet_1_payload"), channel.readPacket())

        assertEquals(
            vectors.concat("tool_hello", "tool_packet_1", "tool_packet_2").toHexString(),
            output.toByteArray().toHexString(),
        )
    }

    private fun Map<String, ByteArray>.concat(vararg names: String): ByteArray =
        names.fold(ByteArray(0)) { bytes, name -> bytes + getValue(name) }
}
