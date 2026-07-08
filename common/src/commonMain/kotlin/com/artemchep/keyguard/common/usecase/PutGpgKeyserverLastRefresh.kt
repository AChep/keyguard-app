package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Instant

interface PutGpgKeyserverLastRefresh : (Instant?) -> IO<Unit>
