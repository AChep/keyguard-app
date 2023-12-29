package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface PutScreenState : (
    String,
    Map<String, Any?>,
) -> IO<Unit>
