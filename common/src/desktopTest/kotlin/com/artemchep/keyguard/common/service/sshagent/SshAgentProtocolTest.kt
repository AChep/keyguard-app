package com.artemchep.keyguard.common.service.sshagent

import com.artemchep.keyguard.common.service.agent.AgentFixtures
import com.artemchep.keyguard.common.service.agent.AgentFixtures.verifiedCaller
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.AuthenticateRequest
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.AuthenticateResponse
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.ErrorCode
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.ErrorResponse
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.PROTOCOL_REVISION
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.IpcRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.IpcResponse
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.ListKeysRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.ListKeysResponse
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.SignDataRequest
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.SignDataResponse
import com.artemchep.keyguard.common.service.sshagent.SshAgentMessages.SshKey
import kotlin.test.Test

/**
 * Checks the encodings in `commonSshAgent/proto/fixtures/`, which the Rust
 * tests check as well: each fixture decodes to the message it stands for.
 */
class SshAgentProtocolTest {
    @Test
    fun requestsDecodeFromTheSharedFixtures() {
        AgentFixtures.check("ssh_agent_requests.pbhex", requests())
    }

    @Test
    fun responsesDecodeFromTheSharedFixtures() {
        AgentFixtures.check("ssh_agent_responses.pbhex", responses(), exact = true)
    }

    /** The messages of the fixtures, by name, as the Rust tests build them. */
    private fun requests() = linkedMapOf(
        "authenticate" to IpcRequest(
            id = 1L,
            authenticate = AuthenticateRequest(ByteArray(32) { 0x11 }, PROTOCOL_REVISION),
        ),
        // An agent from before the revision: the app reads zero and rejects it.
        "authenticate_without_revision" to IpcRequest(
            id = 1L,
            authenticate = AuthenticateRequest(ByteArray(32) { 0x11 }, protocolRevision = 0),
        ),
        "list_keys" to IpcRequest(id = 2L, listKeys = ListKeysRequest(verifiedCaller())),
        "list_keys_without_caller" to IpcRequest(id = 3L, listKeys = ListKeysRequest()),
        "sign_data" to IpcRequest(
            id = 4L,
            signData = SignDataRequest(PUBLIC_KEY, byteArrayOf(1, 2, 3, 4, 5), flags = 4, verifiedCaller()),
        ),
        // The uint32 flags of u32::MAX.
        "sign_data_all_flags" to IpcRequest(
            id = 5L,
            signData = SignDataRequest(PUBLIC_KEY, byteArrayOf(0xFF.toByte()), flags = -1),
        ),
        // The uint64 id of u64::MAX, without a request.
        "no_request" to IpcRequest(id = -1L),
    )

    private fun responses() = linkedMapOf(
        "authenticate" to IpcResponse(
            id = 1L,
            authenticate = AuthenticateResponse(success = true, protocolRevision = PROTOCOL_REVISION),
        ),
        "authenticate_rejected" to IpcResponse(
            id = 1L,
            authenticate = AuthenticateResponse(success = false, protocolRevision = PROTOCOL_REVISION),
        ),
        "list_keys" to IpcResponse(
            id = 2L,
            listKeys = ListKeysResponse(listOf(SshKey("Work", PUBLIC_KEY, "ssh-ed25519", "SHA256:abcdef"))),
        ),
        "list_keys_empty" to IpcResponse(id = 3L, listKeys = ListKeysResponse()),
        "sign_data" to IpcResponse(
            id = 4L,
            signData = SignDataResponse(ByteArray(64) { 0x55 }, "ssh-ed25519"),
        ),
        "error_vault_locked" to IpcResponse(
            id = 5L,
            error = ErrorResponse("Vault is locked", ErrorCode.VAULT_LOCKED),
        ),
        "error_not_authenticated" to IpcResponse(
            id = 6L,
            error = ErrorResponse("Not authenticated", ErrorCode.NOT_AUTHENTICATED),
        ),
        "error_unspecified" to IpcResponse(id = 7L, error = ErrorResponse()),
    )

    private companion object {
        const val PUBLIC_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIA test"
    }
}
