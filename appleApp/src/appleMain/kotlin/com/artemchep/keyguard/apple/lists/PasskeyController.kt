package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.service.passkey.toAddCredentialCipherRequest
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.provider.bitwarden.mapper.toDomain
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.util.webauthn.PasskeyBase64
import com.artemchep.keyguard.util.webauthn.PasskeyCredentialId
import com.artemchep.keyguard.util.webauthn.WebAuthnAllowedCredentialDescriptors
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnAuthenticator
import com.artemchep.keyguard.util.webauthn.WebAuthnRegistrationRequest
import com.artemchep.keyguard.util.webauthn.crypto.PasskeySignatureAlgorithm
import com.artemchep.keyguard.util.webauthn.entity.CreatePasskeyPubKeyCredParams
import com.artemchep.keyguard.util.webauthn.findExcludedPasskeyCredentialOrNull

/**
 * Adapts the AutoFill vault and Swift snapshots to the shared WebAuthn authenticator.
 * The provider owns consent, request lifecycle, and the cross-process store lock.
 */
internal class PasskeyController(
    private val ctx: CoreContext,
) {
    /**
     * One entry per discoverable FIDO2 credential, to register as `ASPasskeyCredentialIdentity`.
     * Empty while the vault is locked.
     */
    suspend fun loadPasskeyIdentities(): List<PasskeyIdentitySnapshot> {
        val state = ctx.currentState() as? VaultState.Main ?: return emptyList()
        val reader = AutofillVaultReader(state.sessionKoin)
        return reader.read().toPasskeyIdentities(reader.accountNames())
    }

    suspend fun loadMatchingPasskeyIdentities(
        rpId: String,
        allowedCredentialIds: List<ByteArray>,
    ): List<PasskeyIdentitySnapshot> {
        val state = ctx.currentState() as? VaultState.Main ?: return emptyList()
        val reader = AutofillVaultReader(state.sessionKoin)
        return reader.read().toPasskeyIdentities(
            accountNames = reader.accountNames(),
            rpId = rpId,
            allowedCredentials = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(allowedCredentialIds),
        )
    }

    /** Called after consent, inside the provider's store lock and before its write. */
    suspend fun hasExcludedPasskeyCredential(rpId: String, credentialIds: List<ByteArray>): Boolean {
        val state = ctx.currentState() as? VaultState.Main
        if (state == null || credentialIds.isEmpty()) return false
        val credentials = AutofillVaultReader(state.sessionKoin).read()
            .flatMap { it.login?.fido2Credentials.orEmpty() }
            .map { it.toDomain().toWebAuthnCredential() }
        return findExcludedPasskeyCredentialOrNull(
            excludedCredentialIds = credentialIds.map(PasskeyCredentialId::decode).toSet(),
            rpId = rpId,
            credentials = credentials,
        ) != null
    }

    /**
     * Computes a WebAuthn assertion for the passkey identified by [recordId]
     * (`accountId|cipherId|credentialId`): builds the assertion authenticator data and
     * signs `authenticatorData ‖ clientDataHash` (the system supplies [clientDataHash]).
     * Null while locked or if the credential is gone. Invalid requests and signing errors throw.
     */
    suspend fun assertPasskey(
        recordId: String,
        clientDataHash: ByteArray,
        expectedRpId: String,
        expectedCredentialId: ByteArray,
        allowedCredentialIds: List<ByteArray>,
        userVerification: String?,
        userVerified: Boolean,
    ): PasskeyAssertionSnapshot? {
        val parts = recordId.split('|', limit = 3)
        if (parts.size != PASSKEY_RECORD_PARTS) return null
        val accountId = parts[0]
        val cipherId = parts[1]
        val credentialId = parts[2]

        val state = ctx.currentState() as? VaultState.Main ?: return null
        val di = state.sessionKoin
        val cipher = AutofillVaultReader(di).read(cipherId)
            .firstOrNull { it.accountId == accountId }
            ?: return null
        val fido2 = cipher.login?.fido2Credentials
            ?.firstOrNull { it.credentialId == credentialId }
            ?.toDomain()
            ?: return null

        if (fido2.rpId != expectedRpId ||
            !PasskeyCredentialId.encode(fido2.credentialId).contentEquals(expectedCredentialId)
        ) return null

        val result = di.get<WebAuthnAuthenticator>().getAssertion(
            request = WebAuthnAssertionHashRequest(
                rpId = expectedRpId,
                clientDataHash = clientDataHash,
                userVerification = userVerification,
                allowedCredentials = WebAuthnAllowedCredentialDescriptors.fromCredentialIds(allowedCredentialIds),
            ),
            credential = fido2.toWebAuthnCredential(),
            userVerified = userVerified,
        )
        return PasskeyAssertionSnapshot(
            credentialId = result.credentialId,
            authenticatorData = result.authenticatorData,
            signature = result.signature,
            userHandle = result.userHandle?.let(PasskeyBase64::decode) ?: ByteArray(0),
            rpId = fido2.rpId,
        )
    }

    /**
     * Creates a new passkey and attaches it to the existing login [cipherRecordId]
     * (`accountId|cipherId`): generates a P-256 key pair, builds the "none"-attestation
     * object, and persists the credential via the shared [AddCredentialCipher] use case.
     * Returns null when the target is unavailable or persistence fails. Crypto errors throw.
     */
    suspend fun createPasskey(
        cipherRecordId: String,
        rpId: String,
        rpName: String?,
        userName: String?,
        userDisplayName: String?,
        userHandle: ByteArray,
        userVerification: String?,
        userVerified: Boolean,
    ): PasskeyRegistrationSnapshot? {
        val parts = cipherRecordId.split('|', limit = 2)
        if (parts.size != 2) return null
        val cipherId = parts[1]

        val state = ctx.currentState() as? VaultState.Main ?: return null
        val di = state.sessionKoin

        val cipher = AutofillVaultReader(di).read(cipherId).firstOrNull { it.accountId == parts[0] }
            ?: return null
        if (cipher.login == null || !cipher.service.canEdit()) return null
        val result = di.get<WebAuthnAuthenticator>().createCredential(
            request = WebAuthnRegistrationRequest(
                rpId = rpId,
                rpName = rpName,
                userHandle = PasskeyBase64.encodeToString(userHandle),
                userName = userName,
                userDisplayName = userDisplayName,
                userVerification = userVerification,
                discoverable = true,
                // The provider rejects requests without ES256 before presenting consent.
                pubKeyCredParams = listOf(
                    CreatePasskeyPubKeyCredParams(PasskeySignatureAlgorithm.ES256.coseValue.toDouble(), "public-key"),
                ),
            ),
            userVerified = userVerified,
        )
        val request = AddCredentialCipherRequest(
            cipherId = cipherId,
            data = result.credential.toAddCredentialCipherRequest(),
        )
        val stored = runCatching { di.get<AddCredentialCipher>()(request).bind() }
            .getOrDefault(false)
        if (!stored) return null

        return PasskeyRegistrationSnapshot(
            credentialId = result.credentialId,
            attestationObject = result.attestationObject,
            rpId = rpId,
            identity = PasskeyIdentitySnapshot(
                recordId = "${parts[0]}|$cipherId|${result.credential.credentialId}",
                rpId = rpId,
                userName = userName.orEmpty(),
                credentialId = result.credentialId,
                userHandle = userHandle,
            ),
        )
    }
}

private const val PASSKEY_RECORD_PARTS = 3

/** Malformed imported metadata must not prevent indexing other valid credentials. */
internal fun List<BitwardenCipher>.toPasskeyIdentities(
    accountNames: Map<String, String> = emptyMap(),
    rpId: String? = null,
    allowedCredentials: WebAuthnAllowedCredentialDescriptors = WebAuthnAllowedCredentialDescriptors(false, emptyList()),
): List<PasskeyIdentitySnapshot> = flatMap { cipher ->
    cipher.login?.fido2Credentials.orEmpty().mapNotNull { fido2 ->
        runCatching {
            val credential = fido2.toDomain().toWebAuthnCredential()
            if ((rpId != null && credential.rpId != rpId) || !allowedCredentials.allows(credential)) {
                return@mapNotNull null
            }
            val credentialId = requireNotNull(fido2.credentialId)
            require(credentialId.isNotBlank() && fido2.rpId.isNotBlank())
            PasskeyIdentitySnapshot(
                recordId = "${cipher.accountId}|${cipher.cipherId}|$credentialId",
                rpId = fido2.rpId,
                userName = fido2.userName ?: cipher.login?.username.orEmpty(),
                credentialId = PasskeyCredentialId.encode(credentialId),
                userHandle = fido2.userHandle?.let(PasskeyBase64::decode) ?: ByteArray(0),
                accountName = accountNames[cipher.accountId].orEmpty(),
                cipherName = cipher.name.orEmpty(),
            )
        }.getOrNull()
    }
}
