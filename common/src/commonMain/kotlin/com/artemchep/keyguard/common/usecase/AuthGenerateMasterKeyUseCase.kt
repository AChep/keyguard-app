package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase

/**
 * Generates a new random master key, wrapped with the key that
 * the password derives with the given key derivation.
 */
interface AuthGenerateMasterKeyUseCase : (MasterKdf.Argon2id) -> AuthMasterKeyUseCase
