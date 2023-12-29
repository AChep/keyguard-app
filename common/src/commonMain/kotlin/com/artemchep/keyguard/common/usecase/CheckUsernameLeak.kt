package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CheckUsernameLeakRequest
import com.artemchep.keyguard.common.model.DHibpC

interface CheckUsernameLeak : (
    CheckUsernameLeakRequest,
) -> IO<DHibpC>
