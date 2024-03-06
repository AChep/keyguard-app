package com.artemchep.keyguard.common.service.filter

import com.artemchep.keyguard.common.io.IO

interface RemoveCipherFilterById : (
    Set<Long>,
) -> IO<Unit>
