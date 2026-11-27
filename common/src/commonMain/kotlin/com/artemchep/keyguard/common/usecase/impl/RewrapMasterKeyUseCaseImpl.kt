package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.usecase.GenerateMasterSaltUseCase
import com.artemchep.keyguard.common.usecase.RewrapMasterKeyUseCase
import kotlinx.coroutines.Dispatchers

class RewrapMasterKeyUseCaseImpl(
    private val masterKeyWrapping: MasterKeyWrapping,
    private val generateMasterSaltUseCase: GenerateMasterSaltUseCase,
) : RewrapMasterKeyUseCase {
    override fun invoke(
        masterKey: MasterKey,
        password: MasterPassword,
        kdf: MasterKdf.Argon2id,
    ): IO<FingerprintPassword.Wrapped> = ioEffect(Dispatchers.Default) {
        val salt = generateMasterSaltUseCase().bind()
        val wrappedKey = masterKeyWrapping.wrap(
            password = password.byteArray,
            salt = salt.byteArray,
            kdf = kdf,
            masterKey = masterKey.byteArray,
        )
        FingerprintPassword.Wrapped(
            kdf = kdf,
            salt = salt,
            wrappedKey = wrappedKey,
        )
    }
}
