package com.artemchep.keyguard.common.service.sshagent

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 * Tests for protobuf serialization round-trips of the IPC message types
 * defined in [SshAgentMessages].
 */
@OptIn(ExperimentalSerializationApi::class)
class SshAgentMessagesTest {
    private val protoBuf = ProtoBuf

    // ================================================================
    // IpcRequest round-trips
    // ================================================================

    @Test
    fun `AuthenticateRequest round-trips correctly`() {
        val token = ByteArray(32) { it.toByte() }
        val original = SshAgentMessages.IpcRequest(
            id = 1L,
            authenticate = SshAgentMessages.AuthenticateRequest(token = token),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(1L, decoded.id)
        assertContentEquals(token, decoded.authenticate?.token)
        assertNull(decoded.listKeys)
        assertNull(decoded.signData)
    }

    @Test
    fun `ListKeysRequest round-trips correctly`() {
        val original = SshAgentMessages.IpcRequest(
            id = 42L,
            listKeys = SshAgentMessages.ListKeysRequest(),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(42L, decoded.id)
        assertNull(decoded.authenticate)
        // ListKeysRequest has no fields, just verify it's not null
        assertTrue(decoded.listKeys != null)
        assertNull(decoded.listKeys?.caller)
        assertNull(decoded.signData)
    }

    @Test
    fun `ListKeysRequest with caller identity round-trips correctly`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "ssh",
            executablePath = "/usr/bin/ssh",
            appPid = 321,
            appName = "Terminal",
            appBundlePath = "/System/Applications/Utilities/Terminal.app",
        )
        val original = SshAgentMessages.IpcRequest(
            id = 43L,
            listKeys = SshAgentMessages.ListKeysRequest(caller = caller),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(43L, decoded.id)
        assertEquals(123, decoded.listKeys?.caller?.pid)
        assertEquals("Terminal", decoded.listKeys?.caller?.appName)
    }

    @Test
    fun `SignDataRequest round-trips correctly`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        val original = SshAgentMessages.IpcRequest(
            id = 7L,
            signData = SshAgentMessages.SignDataRequest(
                publicKey = "ssh-ed25519 AAAA... test",
                data = data,
                flags = 0x04,
            ),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(7L, decoded.id)
        assertNull(decoded.authenticate)
        assertNull(decoded.listKeys)
        val signData = decoded.signData!!
        assertEquals("ssh-ed25519 AAAA... test", signData.publicKey)
        assertContentEquals(data, signData.data)
        assertEquals(0x04, signData.flags)
        assertNull(signData.caller)
    }

    @Test
    fun `SignDataRequest with caller identity round-trips correctly`() {
        val caller = SshAgentMessages.CallerIdentity(
            pid = 123,
            uid = 456,
            gid = 789,
            processName = "ssh",
            executablePath = "/usr/bin/ssh",
            appPid = 321,
            appName = "Terminal",
            appBundlePath = "/System/Applications/Utilities/Terminal.app",
        )
        val original = SshAgentMessages.IpcRequest(
            id = 8L,
            signData = SshAgentMessages.SignDataRequest(
                publicKey = "ssh-ed25519 AAAA... test",
                data = byteArrayOf(1, 2, 3),
                flags = 0,
                caller = caller,
            ),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(8L, decoded.id)
        assertEquals(123, decoded.signData?.caller?.pid)
        assertEquals("ssh", decoded.signData?.caller?.processName)
        assertEquals("Terminal", decoded.signData?.caller?.appName)
    }

    // ================================================================
    // IpcResponse round-trips
    // ================================================================

    @Test
    fun `AuthenticateResponse round-trips correctly`() {
        val original = SshAgentMessages.IpcResponse(
            id = 1L,
            authenticate = SshAgentMessages.AuthenticateResponse(success = true),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(1L, decoded.id)
        assertTrue(decoded.authenticate!!.success)
        assertNull(decoded.listKeys)
        assertNull(decoded.signData)
        assertNull(decoded.error)
    }

    @Test
    fun `ListKeysResponse with multiple keys round-trips correctly`() {
        val original = SshAgentMessages.IpcResponse(
            id = 2L,
            listKeys = SshAgentMessages.ListKeysResponse(
                keys = listOf(
                    SshAgentMessages.SshKey(
                        name = "my-key",
                        publicKey = "ssh-ed25519 AAAA...",
                        keyType = "ssh-ed25519",
                        fingerprint = "SHA256:abcdef",
                    ),
                    SshAgentMessages.SshKey(
                        name = "rsa-key",
                        publicKey = "ssh-rsa BBBB...",
                        keyType = "ssh-rsa",
                        fingerprint = "SHA256:ghijkl",
                    ),
                ),
            ),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(2L, decoded.id)
        val keys = decoded.listKeys!!.keys
        assertEquals(2, keys.size)
        assertEquals("my-key", keys[0].name)
        assertEquals("ssh-ed25519", keys[0].keyType)
        assertEquals("rsa-key", keys[1].name)
        assertEquals("ssh-rsa", keys[1].keyType)
    }

    @Test
    fun `SignDataResponse round-trips correctly`() {
        val signature = ByteArray(64) { 42 }
        val original = SshAgentMessages.IpcResponse(
            id = 3L,
            signData = SshAgentMessages.SignDataResponse(
                signature = signature,
                algorithm = "ssh-ed25519",
            ),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(3L, decoded.id)
        val signData = decoded.signData!!
        assertContentEquals(signature, signData.signature)
        assertEquals("ssh-ed25519", signData.algorithm)
    }

    @Test
    fun `ErrorResponse round-trips correctly`() {
        val original = SshAgentMessages.IpcResponse(
            id = 4L,
            error = SshAgentMessages.ErrorResponse(
                message = "vault is locked",
                code = SshAgentMessages.ErrorCode.VAULT_LOCKED,
            ),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertEquals(4L, decoded.id)
        val error = decoded.error!!
        assertEquals("vault is locked", error.message)
        assertEquals(SshAgentMessages.ErrorCode.VAULT_LOCKED, error.code)
    }

    // ================================================================
    // Edge cases
    // ================================================================

    @Test
    fun `Empty IpcRequest with no variant round-trips`() {
        val original = SshAgentMessages.IpcRequest(id = 99L)

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcRequest>(bytes)

        assertEquals(99L, decoded.id)
        assertNull(decoded.authenticate)
        assertNull(decoded.listKeys)
        assertNull(decoded.signData)
    }

    @Test
    fun `Empty keys list round-trips correctly`() {
        val original = SshAgentMessages.IpcResponse(
            id = 5L,
            listKeys = SshAgentMessages.ListKeysResponse(keys = emptyList()),
        )

        val bytes = protoBuf.encodeToByteArray(original)
        val decoded = protoBuf.decodeFromByteArray<SshAgentMessages.IpcResponse>(bytes)

        assertTrue(decoded.listKeys!!.keys.isEmpty())
    }

    @Test
    fun `ErrorCode constants have expected values`() {
        assertEquals(0, SshAgentMessages.ErrorCode.UNSPECIFIED)
        assertEquals(1, SshAgentMessages.ErrorCode.VAULT_LOCKED)
        assertEquals(2, SshAgentMessages.ErrorCode.USER_DENIED)
        assertEquals(3, SshAgentMessages.ErrorCode.KEY_NOT_FOUND)
        assertEquals(4, SshAgentMessages.ErrorCode.AUTH_FAILED)
        assertEquals(5, SshAgentMessages.ErrorCode.NOT_AUTHENTICATED)
    }
}
