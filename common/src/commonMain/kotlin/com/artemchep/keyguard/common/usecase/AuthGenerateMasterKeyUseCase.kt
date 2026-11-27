package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.core.session.usecase.AuthMasterKeyUseCase

interface AuthGenerateMasterKeyUseCase : (MasterKdf) -> AuthMasterKeyUseCase
