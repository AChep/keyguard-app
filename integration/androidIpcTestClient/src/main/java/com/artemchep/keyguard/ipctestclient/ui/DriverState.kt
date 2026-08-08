package com.artemchep.keyguard.ipctestclient.ui

import com.artemchep.keyguard.ipctestclient.ipc.OpenPgpOperation
import com.artemchep.keyguard.ipctestclient.ipc.SshOperation
import com.artemchep.keyguard.ipctestclient.ipc.toKeyIdHex
import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * The request payload, which is text while it is being typed and bytes once it
 * comes from a previous result. Ciphertext is not text, so round-tripping
 * encrypt into decrypt has to carry the raw bytes.
 */
sealed interface PayloadSource {
    data class Text(val value: String) : PayloadSource

    class Binary(val label: String, val bytes: ByteArray) : PayloadSource

    fun toBytes(): ByteArray = when (this) {
        is Text -> value.encodeToByteArray()
        is Binary -> bytes
    }
}

/** Values carried between operations so a round trip is a few taps, not retyping. */
data class Scratchpad(
    val signKeyId: Long? = null,
    val keyIds: List<Long> = emptyList(),
    val primaryUserId: String? = null,
    val detachedSignature: ByteArray? = null,
    val lastOutput: ByteArray? = null,
    val sshKeyId: String? = null,
    val sshPublicKey: String? = null,
    val sshSignature: ByteArray? = null,
) {
    fun summary(): List<Pair<String, String>> = buildList {
        signKeyId?.let { add("sign key id" to it.toKeyIdHex()) }
        if (keyIds.isNotEmpty()) {
            add("key ids" to keyIds.joinToString { it.toKeyIdHex() })
        }
        primaryUserId?.let { add("primary user id" to it) }
        detachedSignature?.let { add("detached signature" to "byte[${it.size}]") }
        lastOutput?.let { add("last output" to "byte[${it.size}]") }
        sshKeyId?.let { add("ssh key id" to it) }
        sshPublicKey?.let { add("ssh public key" to it) }
        sshSignature?.let { add("ssh signature" to "byte[${it.size}]") }
    }
}

@Suppress("LongParameterList")
data class OpenPgpFormState(
    val operation: OpenPgpOperation = OpenPgpOperation.CHECK_PERMISSION,
    val apiVersion: String = OpenPgpOperation.DEFAULT_API_VERSION.toString(),
    val omitApiVersion: Boolean = false,
    val payload: PayloadSource = PayloadSource.Text(DEFAULT_PAYLOAD),
    val userIds: String = "",
    val sendAsSingleUserId: Boolean = false,
    val keyIds: String = "",
    val selectedKeyIds: String = "",
    val signKeyId: String = "",
    val sendSignKeyIdAsPreselect: Boolean = false,
    val keyId: String = "",
    val originalFilename: String = "",
    val asciiArmor: Boolean? = null,
    val enableCompression: Boolean? = null,
    val opportunistic: Boolean? = null,
    val detachedSignature: ByteArray? = null,
    val customHeaders: Boolean = false,
    val minimize: Boolean = false,
    val omitInput: Boolean = false,
    val omitOutputPipe: Boolean = false,
    val foreignPipeId: String = "",
) {
    companion object {
        const val DEFAULT_PAYLOAD = "hello from the Keyguard IPC test client"
    }
}

data class SshFormState(
    val operation: SshOperation = SshOperation.SELECT_KEY,
    val apiVersion: String = SshAuthenticationApi.API_VERSION.toString(),
    val omitApiVersion: Boolean = false,
    val keyId: String = "",
    val challenge: String = DEFAULT_CHALLENGE,
    val challengeFromScratch: Boolean = false,
    val hashAlgorithm: Int = SshAuthenticationApi.SHA256,
    val useLibraryBuilders: Boolean = true,
) {
    companion object {
        const val DEFAULT_CHALLENGE = "keyguard-ipc-test-client-challenge"
    }
}
