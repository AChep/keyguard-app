package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig

interface GetPassphrase : (PasswordGeneratorConfig.Passphrase) -> IO<String>
