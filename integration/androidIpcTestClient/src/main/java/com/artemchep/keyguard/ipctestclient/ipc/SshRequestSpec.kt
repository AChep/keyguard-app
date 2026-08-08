package com.artemchep.keyguard.ipctestclient.ipc

import android.content.Intent
import org.openintents.ssh.authentication.SshAuthenticationApi
import org.openintents.ssh.authentication.request.KeySelectionRequest
import org.openintents.ssh.authentication.request.PublicKeyRequest
import org.openintents.ssh.authentication.request.SigningRequest
import org.openintents.ssh.authentication.request.SshPublicKeyRequest

/**
 * One SSH Authentication API request.
 *
 * [useLibraryBuilders] builds the intent with the official request classes,
 * which is what a real client does and what the provider's responses are shaped
 * against. They cannot express a missing action or a missing key id, so the
 * negative probes turn it off and hand-build the intent instead. Either way the
 * API version extra is added here: the builders never write it, and
 * [SshAuthenticationApi.executeApi] - which does - hardcodes it.
 */
data class SshRequestSpec(
    val operation: SshOperation,
    val apiVersion: Int? = SshAuthenticationApi.API_VERSION,
    val keyId: String? = null,
    val challenge: ByteArray = ByteArray(0),
    val hashAlgorithm: Int = SshAuthenticationApi.SHA256,
    val actionOverride: String? = null,
    val omitAction: Boolean = false,
    val omitHashAlgorithm: Boolean = false,
    val useLibraryBuilders: Boolean = true,
) {
    val action: String? get() = if (omitAction) null else actionOverride ?: operation.action

    fun toIntent(): Intent {
        val supportsLibraryBuilder =
            !omitAction &&
                !omitHashAlgorithm &&
                actionOverride == null
        val intent = if (useLibraryBuilders && supportsLibraryBuilder) {
            buildWithLibrary()
        } else {
            buildManually()
        }
        apiVersion?.let { intent.putExtra(SshAuthenticationApi.EXTRA_API_VERSION, it) }
        return intent
    }

    private fun buildWithLibrary(): Intent = when (operation) {
        SshOperation.SELECT_KEY -> KeySelectionRequest()
        SshOperation.GET_PUBLIC_KEY -> PublicKeyRequest(keyId)
        SshOperation.GET_SSH_PUBLIC_KEY -> SshPublicKeyRequest(keyId)
        SshOperation.SIGN -> SigningRequest(challenge, keyId, hashAlgorithm)
        SshOperation.UNKNOWN -> return buildManually()
    }.toIntent()

    private fun buildManually(): Intent {
        val intent = Intent()
        action?.let(intent::setAction)
        keyId?.let { intent.putExtra(SshAuthenticationApi.EXTRA_KEY_ID, it) }
        if (operation.needsChallenge) {
            intent.putExtra(SshAuthenticationApi.EXTRA_CHALLENGE, challenge)
            if (!omitHashAlgorithm) {
                intent.putExtra(SshAuthenticationApi.EXTRA_HASH_ALGORITHM, hashAlgorithm)
            }
        }
        return intent
    }

    override fun equals(other: Any?): Boolean = this === other || (
        other is SshRequestSpec &&
            operation == other.operation &&
            apiVersion == other.apiVersion &&
            keyId == other.keyId &&
            challenge.contentEquals(other.challenge) &&
            hashAlgorithm == other.hashAlgorithm &&
            actionOverride == other.actionOverride &&
            omitAction == other.omitAction &&
            omitHashAlgorithm == other.omitHashAlgorithm &&
            useLibraryBuilders == other.useLibraryBuilders
        )

    override fun hashCode(): Int {
        var result = operation.hashCode()
        result = HASH_FACTOR * result + apiVersion.hashCode()
        result = HASH_FACTOR * result + keyId.hashCode()
        result = HASH_FACTOR * result + challenge.contentHashCode()
        result = HASH_FACTOR * result + hashAlgorithm
        result = HASH_FACTOR * result + omitHashAlgorithm.hashCode()
        return result
    }

    private companion object {
        const val HASH_FACTOR = 31
    }
}
