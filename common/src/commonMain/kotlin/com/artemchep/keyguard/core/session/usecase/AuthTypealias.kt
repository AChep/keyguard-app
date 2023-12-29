package com.artemchep.keyguard.core.session.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AuthResult
import com.artemchep.keyguard.common.model.MasterPassword

typealias AuthMasterKeyUseCase = (MasterPassword) -> IO<AuthResult>
