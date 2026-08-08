package com.artemchep.keyguard.ipctestclient.ipc

import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * The SSH Authentication API surface, as Keyguard implements it.
 *
 * [UNKNOWN] is not part of the API; it exists so the driver and the suite can
 * send an action the provider must reject with `UNKNOWN_ACTION`.
 */
enum class SshOperation(
    val action: String,
    val label: String,
    val needsKeyId: Boolean = false,
    val needsChallenge: Boolean = false,
    val usesPrivateKey: Boolean = false,
    val supported: Boolean = true,
) {
    SELECT_KEY(
        action = SshAuthenticationApi.ACTION_SELECT_KEY,
        label = "Select key",
    ),
    GET_PUBLIC_KEY(
        action = SshAuthenticationApi.ACTION_GET_PUBLIC_KEY,
        label = "Get public key (SPKI DER)",
        needsKeyId = true,
    ),
    GET_SSH_PUBLIC_KEY(
        action = SshAuthenticationApi.ACTION_GET_SSH_PUBLIC_KEY,
        label = "Get SSH public key",
        needsKeyId = true,
    ),
    SIGN(
        action = SshAuthenticationApi.ACTION_SIGN,
        label = "Sign challenge",
        needsKeyId = true,
        needsChallenge = true,
        usesPrivateKey = true,
    ),
    UNKNOWN(
        action = "org.openintents.ssh.action.UNKNOWN",
        label = "Unknown action (unsupported)",
        supported = false,
    ),
    ;

    companion object {
        val SUPPORTED: List<SshOperation> = entries.filter { it.supported }

        /** Every hash selector declared by SSH Authentication API v1. */
        val API_HASH_ALGORITHMS = listOf(
            SshAuthenticationApi.SHA1,
            SshAuthenticationApi.SHA224,
            SshAuthenticationApi.SHA256,
            SshAuthenticationApi.SHA384,
            SshAuthenticationApi.SHA512,
            SshAuthenticationApi.RIPEMD160,
        )

        val RSA_HASH_ALGORITHMS = listOf(
            SshAuthenticationApi.SHA1,
            SshAuthenticationApi.SHA256,
            SshAuthenticationApi.SHA512,
        )

        val RSA_UNSUPPORTED_HASH_ALGORITHMS = listOf(
            SshAuthenticationApi.SHA224,
            SshAuthenticationApi.SHA384,
            SshAuthenticationApi.RIPEMD160,
        )

        val UNKNOWN_HASH_ALGORITHMS = listOf(-1, 6, Int.MAX_VALUE)

        const val MAX_KEY_ID_LENGTH = 512
        const val MAX_CHALLENGE_BYTES = 1024 * 1024
    }
}
