package com.artemchep.keyguard.feature.passkeys

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AddCipherUsedPasskeyHistoryRequest
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.model.DCollection
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.passkey.availablePasskeys
import com.artemchep.keyguard.common.service.passkey.storedPasskeyCredentials
import com.artemchep.keyguard.common.service.passkey.toAddCredentialCipherRequest
import com.artemchep.keyguard.common.service.passkey.toWebAuthnCredential
import com.artemchep.keyguard.common.usecase.AddCipherUsedPasskeyHistory
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetVaultSession
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridCandidate
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridSaveTarget
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridVault
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridVaultSession
import com.artemchep.keyguard.feature.passkeyhybrid.presentation.HybridWriteException
import com.artemchep.keyguard.provider.bitwarden.usecase.util.canEdit
import com.artemchep.keyguard.util.webauthn.WebAuthnAssertionHashRequest
import com.artemchep.keyguard.util.webauthn.WebAuthnCredential
import com.artemchep.keyguard.util.webauthn.canAnswer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Application models and vault policy stay outside the pure hybrid feature. Shared by platform adapters. */
class HybridVaultAdapter(
    private val getSession: GetVaultSession,
    private val visibleCiphers: Flow<List<DSecret>>,
    private val ciphers: Flow<List<DSecret>>,
    private val canWrite: Flow<Boolean>,
    private val collections: Flow<List<DCollection>>,
    private val addCredential: AddCredentialCipher,
    private val recordUsage: AddCipherUsedPasskeyHistory,
) : HybridVault {
    override suspend fun openSession(): HybridVaultSession? {
        val session = getSession().first() as? MasterSession.Key ?: return null
        return object : HybridVaultSession {
            override val active = session.session.active

            override fun requireActive() {
                check(active.value)
                check((getSession.valueOrNull as? MasterSession.Key)?.session === session.session)
            }
        }
    }

    override suspend fun candidates(request: WebAuthnAssertionHashRequest): List<HybridCandidate> =
        visibleCiphers
            .first()
            .availablePasskeys()
            .filter { (_, credential) ->
                credential.toWebAuthnCredential().canAnswer(request.rpId, request.allowedCredentials)
            }.map { (cipher, credential) -> credential.candidate(cipher) }

    override suspend fun credential(candidate: HybridCandidate): WebAuthnCredential? =
        visibleCiphers
            .first()
            .availablePasskeys()
            .firstOrNull { (cipher, credential) ->
                cipher.id == candidate.cipherId &&
                    cipher.accountId == candidate.accountId &&
                    credential.credentialId == candidate.credentialId
            }?.second
            ?.toWebAuthnCredential()

    override suspend fun requireWritable(target: HybridSaveTarget) {
        writableCipher(target)
    }

    private suspend fun writableCipher(target: HybridSaveTarget): DSecret {
        val cipher =
            ciphers.first().firstOrNull { it.accountId == target.accountId && it.id == target.cipherId }
                ?: throw HybridWriteException()
        val isLogin = !cipher.deleted && !cipher.archived && cipher.type == DSecret.Type.Login
        val isEditable = cipher.service.canEdit() && canWrite.first()
        val readOnlyCollection =
            collections.first().any {
                it.accountId == cipher.accountId && it.id in cipher.collectionIds && it.readOnly
            }
        if (!isLogin || !isEditable || readOnlyCollection) throw HybridWriteException()
        return cipher
    }

    override suspend fun credentials(): List<WebAuthnCredential> =
        ciphers.first().storedPasskeyCredentials().map { it.toWebAuthnCredential() }

    override suspend fun save(
        target: HybridSaveTarget,
        credential: WebAuthnCredential,
    ): Boolean {
        val cipher = writableCipher(target)
        val request = AddCredentialCipherRequest(cipher.id, credential.toAddCredentialCipherRequest())
        return addCredential(request).attempt().bind().getOrNull() == AddCredentialCipher.Result.Stored
    }

    override suspend fun recordUsage(candidate: HybridCandidate) {
        recordUsage(AddCipherUsedPasskeyHistoryRequest(candidate.accountId, candidate.cipherId, candidate.credentialId))
            .attempt()
            .bind()
    }
}

private fun DSecret.Login.Fido2Credentials.candidate(cipher: DSecret) =
    HybridCandidate(
        accountId = cipher.accountId,
        cipherId = cipher.id,
        credentialId = credentialId,
        title = cipher.name,
        username = userDisplayName?.takeIf { it.isNotBlank() } ?: userName.orEmpty(),
    )
