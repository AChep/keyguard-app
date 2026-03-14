package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.flatten
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasskeyData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequestPasswordData
import com.artemchep.keyguard.common.model.AddCredentialCipherRequest
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.AddCredentialCipher
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.core.store.bitwarden.BitwardenCipher
import com.artemchep.keyguard.data.bitwarden.Cipher
import com.artemchep.keyguard.provider.bitwarden.usecase.util.ModifyCipherById
import kotlin.time.Clock
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.collections.orEmpty
import kotlin.collections.plus
import kotlin.time.Instant

/**
 * @author Artem Chepurnyi
 */
class AddCredentialCipherImpl(
    private val modifyCipherById: ModifyCipherById,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val getPasswordStrength: GetPasswordStrength,
) : AddCredentialCipher {
    companion object {
        private const val TAG = "AddCredentialCipher"
    }

    constructor(directDI: DirectDI) : this(
        modifyCipherById = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        getPasswordStrength = directDI.instance(),
    )

    override fun invoke(
        request: AddCredentialCipherRequest,
    ): IO<Boolean> = ioEffect {
        val createdAt = Clock.System.now()
        modifyCipherById(
            setOf(request.cipherId),
        ) { model ->
            when (val data = request.data) {
                is AddCredentialCipherRequestPasskeyData -> {
                    addPasskeyCredential(
                        cipher = model,
                        data = data,
                        createdAt = createdAt,
                    )
                }
                is AddCredentialCipherRequestPasswordData -> {
                    addPasswordCredential(
                        cipher = model,
                        data = data,
                        createdAt = createdAt,
                    )
                }
            }
        }
            // Report that we have actually modified the
            // ciphers.
            .map { changedCipherIds ->
                request.cipherId in changedCipherIds
            }
    }
        .flatten()

    private suspend fun addPasskeyCredential(
        cipher: Cipher,
        data: AddCredentialCipherRequestPasskeyData,
        createdAt: Instant,
    ): Cipher {
        var new = cipher

        val oldCredentials = cipher.data_.login?.fido2Credentials.orEmpty()
        val newCredentials = kotlin.run {
            val passkey = BitwardenCipher.Login.Fido2Credentials(
                credentialId = data.credentialId,
                keyType = data.keyType,
                keyAlgorithm = data.keyAlgorithm,
                keyCurve = data.keyCurve,
                keyValue = data.keyValue,
                rpId = data.rpId,
                rpName = data.rpName,
                counter = data.counter?.toString().orEmpty(),
                userHandle = data.userHandle,
                userName = data.userName,
                userDisplayName = data.userDisplayName,
                discoverable = data.discoverable.toString(),
                creationDate = createdAt,
            )
            listOf(passkey)
        }
        if (newCredentials.isEmpty()) {
            return new
        }
        new = new.copy(
            data_ = new.data_.copy(
                login = new.data_.login?.copy(
                    fido2Credentials = oldCredentials + newCredentials,
                ),
            ),
        )
        return new
    }

    private suspend fun addPasswordCredential(
        cipher: Cipher,
        data: AddCredentialCipherRequestPasswordData,
        createdAt: Instant,
    ): Cipher {
        var new = cipher

        val newLogin = BitwardenCipher.Login.of(
            cryptoGenerator = cryptoGenerator,
            base64Service = base64Service,
            getPasswordStrength = getPasswordStrength,
            now = createdAt,
            old = cipher.data_,
            // new fields
            _username = data.id,
            _password = data.password,
        )
        new = new.copy(
            data_ = new.data_.copy(
                login = newLogin,
            ),
        )
        return new
    }
}
