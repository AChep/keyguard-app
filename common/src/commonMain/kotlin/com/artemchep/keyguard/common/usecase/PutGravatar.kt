package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface PutGravatar : (Boolean) -> IO<Unit>
