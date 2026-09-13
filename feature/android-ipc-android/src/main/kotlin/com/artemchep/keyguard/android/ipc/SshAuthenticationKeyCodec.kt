package com.artemchep.keyguard.android.ipc

import com.artemchep.keyguard.nativecrypto.NativeCryptoSsh
import com.artemchep.keyguard.nativecrypto.NativeSshKeyType
import com.artemchep.keyguard.nativecrypto.NativeSshPublicKey
import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * Glue between the shared native SSH implementation ([NativeCryptoSsh]) and
 * the OpenIntents SSH Authentication API contract. Key parsing, SPKI
 * encoding, and signing all live in the shared layer; only the API enum and
 * flag mapping is Android-specific.
 */
internal fun NativeSshPublicKey.toSshAuthenticationApiAlgorithm(): Int = when (type) {
    NativeSshKeyType.RSA -> SshAuthenticationApi.RSA
    NativeSshKeyType.ED25519 -> SshAuthenticationApi.EDDSA
}

/** Every hash selector declared by SSH Authentication API v1. */
internal val SSH_AUTHENTICATION_API_HASH_ALGORITHMS: Set<Int> = setOf(
    SshAuthenticationApi.SHA1,
    SshAuthenticationApi.SHA224,
    SshAuthenticationApi.SHA256,
    SshAuthenticationApi.SHA384,
    SshAuthenticationApi.SHA512,
    SshAuthenticationApi.RIPEMD160,
)

/**
 * Maps the OpenIntents hash selection to the ssh-agent signature flags
 * consumed by [NativeCryptoSsh.sign], or null when the key type and hash
 * combination is unsupported.
 */
internal fun sshAgentSignatureFlags(
    keyType: String,
    hashAlgorithm: Int,
): Int? = when (keyType) {
    // Ed25519 always uses its fixed internal hash behavior. The API's supported
    // hash values are accepted for compatibility but do not alter the signature.
    NativeCryptoSsh.ALGORITHM_SSH_ED25519 -> when (hashAlgorithm) {
        in SSH_AUTHENTICATION_API_HASH_ALGORITHMS -> 0
        else -> null
    }

    NativeCryptoSsh.ALGORITHM_SSH_RSA -> when (hashAlgorithm) {
        SshAuthenticationApi.SHA1 -> 0
        SshAuthenticationApi.SHA256 -> NativeCryptoSsh.AGENT_FLAG_RSA_SHA2_256
        SshAuthenticationApi.SHA512 -> NativeCryptoSsh.AGENT_FLAG_RSA_SHA2_512
        else -> null
    }

    else -> null
}

internal fun isSupportedSshAuthenticationApiVersion(version: Int): Boolean =
    version == SshAuthenticationApi.API_VERSION
