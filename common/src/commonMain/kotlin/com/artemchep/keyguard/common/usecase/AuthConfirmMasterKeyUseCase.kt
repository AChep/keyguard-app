package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase

interface AuthConfirmMasterKeyUseCase : (
    MasterPasswordSalt,
    MasterPasswordHash,
) -> AuthMasterKeyUseCase

