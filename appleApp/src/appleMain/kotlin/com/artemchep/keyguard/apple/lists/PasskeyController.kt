package com.artemchep.keyguard.apple.lists

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.runCatchingNonFatal
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
import com.artemchep.keyguard.util.webauthn.wireIfCanAnswer
import com.artemchep.keyguard.util.webauthn.wireIfIndexable

/**
 * Adapts the AutoFill vault and Swift snapshots to the shared WebAuthn authenticator.
 * The provider owns consent, request lifecycle, and the cross-process store lock.
 */
internal class PasskeyController(
    private val ctx: CoreContext,
) {
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

    suspend fun hasExcludedPasskeyCredential(rpId: String, credentialIds: List<ByteArray>): Boolean {
        val state = ctx.currentState() as? VaultState.Main
        if (state == null || credentialIds.isEmpty()) return false
        // Like the other platforms: an archived passkey is still stored, so it still excludes
        // another one for the account, while the trash does not.
        val credentials = AutofillVaultReader(state.sessionKoin).read(includeArchived = true)
            .flatMap { it.login?.fido2Credentials.orEmpty() }
            .map { it.toDomain().toWebAuthnCredential() }
        return findExcludedPasskeyCredentialOrNull(
            excludedCredentialIds = credentialIds.map(PasskeyCredentialId::decode).toSet(),
            rpId = rpId,
            credentials = credentials,
        ) != null
    }

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
            PasskeyCredentialId.encodeOrNull(fido2.credentialId)?.contentEquals(expectedCredentialId) != true
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
            userHandle = result.userHandle ?: ByteArray(0),
            rpId = fido2.rpId,
        )
    }

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
        // A failed save returns null; a cancelled request stays cancelled.
        val stored = runCatchingNonFatal { di.get<AddCredentialCipher>()(request).bind() }
            .getOrNull()
        if (stored != AddCredentialCipher.Result.Stored) return null

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

/**
 * The passkeys that can answer a request for [rpId] that allows the [allowedCredentials],
 * or without an [rpId], the system index: those that can answer any request for their
 * relying party. Malformed imported metadata never prevents listing other credentials.
 */
internal fun List<BitwardenCipher>.toPasskeyIdentities(
    accountNames: Map<String, String> = emptyMap(),
    rpId: String? = null,
    allowedCredentials: WebAuthnAllowedCredentialDescriptors = WebAuthnAllowedCredentialDescriptors.NONE,
): List<PasskeyIdentitySnapshot> = flatMap { cipher ->
    cipher.login?.fido2Credentials.orEmpty().mapNotNull { fido2 ->
        val credential = fido2.toDomain().toWebAuthnCredential()
        // Lists exactly the passkeys the authenticator can then sign with.
        val wire = if (rpId != null) {
            credential.wireIfCanAnswer(rpId, allowedCredentials)
        } else {
            credential.wireIfIndexable()
        } ?: return@mapNotNull null
        PasskeyIdentitySnapshot(
            recordId = "${cipher.accountId}|${cipher.cipherId}|${credential.credentialId}",
            rpId = credential.rpId,
            userName = fido2.userName ?: cipher.login?.username.orEmpty(),
            credentialId = wire.credentialId,
            userHandle = wire.userHandle ?: ByteArray(0),
            accountName = accountNames[cipher.accountId].orEmpty(),
            cipherName = cipher.name.orEmpty(),
        )
    }
}
