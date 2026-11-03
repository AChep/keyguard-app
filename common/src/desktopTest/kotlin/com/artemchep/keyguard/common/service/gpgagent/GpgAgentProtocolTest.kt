package com.artemchep.keyguard.common.service.gpgagent

import com.artemchep.keyguard.common.service.agent.AgentFixtures
import com.artemchep.keyguard.common.service.agent.AgentFixtures.verifiedCaller
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.AuthenticateRequest
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.AuthenticateResponse
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.ErrorCode
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.ErrorResponse
import com.artemchep.keyguard.common.service.agent.AgentIpcMessages.PROTOCOL_REVISION
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.GpgKey
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.IpcRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.IpcResponse
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.ListKeysRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.ListKeysResponse
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.PkdecryptRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.PkdecryptResponse
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.SignHashRequest
import com.artemchep.keyguard.common.service.gpgagent.GpgAgentMessages.SignHashResponse
import kotlin.test.Test

/**
 * Checks the encodings in `commonGpgAgent/proto/fixtures/`, which the Rust
 * tests check as well: each fixture decodes to the message it stands for.
 */
class GpgAgentProtocolTest {
    @Test
    fun requestsDecodeFromTheSharedFixtures() {
        AgentFixtures.check("gpg_agent_requests.pbhex", requests())
    }

    @Test
    fun responsesDecodeFromTheSharedFixtures() {
        AgentFixtures.check("gpg_agent_responses.pbhex", responses(), exact = true)
    }

    /** The messages of the fixtures, by name, as the Rust tests build them. */
    private fun requests() = linkedMapOf(
        "authenticate" to IpcRequest(
            id = 1L,
            authenticate = AuthenticateRequest(ByteArray(32) { 0x11 }, PROTOCOL_REVISION),
        ),
        "list_keys" to IpcRequest(id = 2L, listKeys = ListKeysRequest(verifiedCaller())),
        "sign_hash" to IpcRequest(
            id = 3L,
            signHash = SignHashRequest(KEYGRIP, "sha256", ByteArray(32) { 0x66 }, verifiedCaller()),
        ),
        // `PKDECRYPT --kem=PGP`: the agent unwraps the session key.
        "pkdecrypt" to IpcRequest(id = 4L, pkdecrypt = pkdecrypt(unwrapEcdh = true)),
        // Plain `PKDECRYPT`: gpg unwraps the shared secret itself.
        "pkdecrypt_legacy" to IpcRequest(id = 5L, pkdecrypt = pkdecrypt(unwrapEcdh = false)),
    )

    private fun pkdecrypt(unwrapEcdh: Boolean) = PkdecryptRequest(
        keygrip = KEYGRIP,
        ciphertext = "(7:enc-val(4:ecdh(1:s1:x)(1:e1:y)))".encodeToByteArray(),
        caller = verifiedCaller(),
        unwrapEcdh = unwrapEcdh,
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
            listKeys = ListKeysResponse(
                listOf(
                    GpgKey(
                        name = "Work <work@example.com>",
                        keygrip = KEYGRIP,
                        fingerprint = "AAAABBBBCCCCDDDDEEEEFFFF0000111122223333",
                        algorithm = "ed25519",
                        canSign = true,
                    ),
                    GpgKey(
                        name = "Work <work@example.com>",
                        keygrip = "76543210FEDCBA9876543210FEDCBA9876543210",
                        fingerprint = "3333222211110000FFFFEEEEDDDDCCCCBBBBAAAA",
                        algorithm = "cv25519",
                        canDecrypt = true,
                    ),
                ),
            ),
        ),
        "sign_hash" to IpcResponse(id = 3L, signHash = SignHashResponse("(sig-val(eddsa(r #01#)(s #02#)))")),
        "pkdecrypt" to IpcResponse(id = 4L, pkdecrypt = PkdecryptResponse("(value #0303#)")),
        "error_unsupported" to IpcResponse(
            id = 5L,
            error = ErrorResponse("Unsupported", ErrorCode.UNSUPPORTED),
        ),
        "error_user_denied" to IpcResponse(
            id = 6L,
            error = ErrorResponse("User denied", ErrorCode.USER_DENIED),
        ),
    )

    private companion object {
        const val KEYGRIP = "0123456789ABCDEF0123456789ABCDEF01234567"
    }
}
