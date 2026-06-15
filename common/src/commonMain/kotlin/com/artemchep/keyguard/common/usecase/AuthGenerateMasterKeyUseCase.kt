package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase

interface AuthGenerateMasterKeyUseCase : (MasterKdfVersion) -> AuthMasterKeyUseCase
