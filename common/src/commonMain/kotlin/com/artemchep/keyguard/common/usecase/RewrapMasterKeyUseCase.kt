package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword

/**
 * Wraps the given master key with the key that the password derives
 * with the given key derivation, under a new salt.
 */
interface RewrapMasterKeyUseCase : (
    MasterKey,
    MasterPassword,
    MasterKdf.Argon2id,
) -> IO<FingerprintPassword.Wrapped>
