package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig

interface GetPassword : (
    GeneratorContext,
    PasswordGeneratorConfig,
) -> IO<String?>
