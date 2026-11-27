package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.FingerprintPassword
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase

/**
 * Returns the master key that the password opens in the given
 * token, or fails with a password mismatch.
 */
interface AuthConfirmMasterKeyUseCase : (FingerprintPassword) -> AuthMasterKeyUseCase
