package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CheckPasswordSetLeakRequest
import com.artemchep.keyguard.common.model.PasswordPwnage

interface CheckPasswordSetLeak : (
    CheckPasswordSetLeakRequest,
) -> IO<Map<String, PasswordPwnage?>>
