package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterPassword
import com.artemchep.keyguard.common.model.MasterPasswordHash
import com.artemchep.keyguard.common.model.MasterPasswordSalt

interface GenerateMasterHashUseCase : (
    MasterPassword,
    MasterPasswordSalt,
    MasterKdfVersion,
) -> IO<MasterPasswordHash>
