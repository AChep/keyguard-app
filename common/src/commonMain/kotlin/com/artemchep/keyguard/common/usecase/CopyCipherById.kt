package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.create.CreateRequest

interface CopyCipherById : (
    Map<String, CreateRequest.Ownership2>,
) -> IO<Unit>
