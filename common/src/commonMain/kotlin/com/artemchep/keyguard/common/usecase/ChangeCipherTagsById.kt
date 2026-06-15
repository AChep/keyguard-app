package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

interface ChangeCipherTagsById : (
    Map<String, List<String>>,
) -> IO<Unit>
