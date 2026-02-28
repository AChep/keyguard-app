package com.artemchep.keyguard.common.service.sshagent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalSerializationApi::class)
class ProstDecodeTest {
    private val protoBuf = ProtoBuf

    @Test
    fun `decode prost-encoded AuthenticateRequest bytes`() {
        val hex = "080112220a20fba9b3fd1613d5eaa05cfc2bb7e0988e0bb06e5e88dbd85db75322912de256f3"
        val bytes = hexToBytes(hex)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)
        assertEquals(1L, decoded.id)
        assertNull(decoded.listKeys)
        assertNull(decoded.signData)
        val auth = decoded.authenticate!!
        assertEquals(32, auth.token.size)
    }

    @Test
    fun `decode prost-encoded SignDataRequest bytes`() {
        val hex = "080722230a187373682d6564323535313920414141412e2e2e2074657374120501020304051804"
        val bytes = hexToBytes(hex)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(7L, decoded.id)
        assertNull(decoded.authenticate)
        assertNull(decoded.listKeys)
        val signData = decoded.signData!!
        assertEquals("ssh-ed25519 AAAA... test", signData.publicKey)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), signData.data)
        assertEquals(0x04, signData.flags)
        assertEquals(hex, protoBuf.encodeToByteArray(decoded).toHex())
    }

    @Test
    fun `decode prost-encoded ListKeysResponse bytes`() {
        val hex = "08021a3b0a390a066d792d6b657912137373682d6564323535313920414141412e2e2e1a0b7373682d65643235353139220d5348413235363a616263646566"
        val bytes = hexToBytes(hex)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(2L, decoded.id)
        assertNull(decoded.authenticate)
        assertNull(decoded.signData)
        assertNull(decoded.error)
        val key = decoded.listKeys!!.keys.single()
        assertEquals("my-key", key.name)
        assertEquals("ssh-ed25519 AAAA...", key.publicKey)
        assertEquals("ssh-ed25519", key.keyType)
        assertEquals("SHA256:abcdef", key.fingerprint)
        assertEquals(hex, protoBuf.encodeToByteArray(decoded).toHex())
    }

    @Test
    fun `decode prost-encoded ErrorResponse bytes`() {
        val hex = "08047a130a0f7661756c74206973206c6f636b65641001"
        val bytes = hexToBytes(hex)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(4L, decoded.id)
        assertNull(decoded.authenticate)
        assertNull(decoded.listKeys)
        assertNull(decoded.signData)
        val error = decoded.error!!
        assertEquals("vault is locked", error.message)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, error.code)
        assertEquals(hex, protoBuf.encodeToByteArray(decoded).toHex())
    }

    @Test
    fun `compare kotlinx vs prost encoding`() {
        val token = byteArrayOf(
            0xfb.toByte(), 0xa9.toByte(), 0xb3.toByte(), 0xfd.toByte(),
            0x16, 0x13, 0xd5.toByte(), 0xea.toByte(),
            0xa0.toByte(), 0x5c, 0xfc.toByte(), 0x2b,
            0xb7.toByte(), 0xe0.toByte(), 0x98.toByte(), 0x8e.toByte(),
            0x0b, 0xb0.toByte(), 0x6e, 0x5e,
            0x88.toByte(), 0xdb.toByte(), 0xd8.toByte(), 0x5d,
            0xb7.toByte(), 0x53, 0x22, 0x91.toByte(),
            0x2d, 0xe2.toByte(), 0x56, 0xf3.toByte(),
        )
        val original = SshAgentMessages.IpcRequest(
            id = 1L,
            authenticate = SshAgentMessages.AuthenticateRequest(token = token),
        )
        val kotlinxBytes = protoBuf.encodeToByteArray(original)
        val kotlinxHex = kotlinxBytes.joinToString("") { "%02x".format(it) }
        val prostHex = "080112220a20fba9b3fd1613d5eaa05cfc2bb7e0988e0bb06e5e88dbd85db75322912de256f3"
        println("kotlinx hex: $kotlinxHex")
        println("prost hex:   $prostHex")
        println("match: ${kotlinxHex == prostHex}")
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
