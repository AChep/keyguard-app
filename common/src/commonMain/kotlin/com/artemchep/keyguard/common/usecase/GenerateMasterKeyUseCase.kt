package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash

interface GenerateMasterKeyUseCase : (
    MasterPassword,
    MasterPasswordHash,
) -> IO<MasterKey>
