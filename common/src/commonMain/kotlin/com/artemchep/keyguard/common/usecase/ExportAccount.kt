package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DFilter

interface ExportAccount : (
    DFilter,
    String,
    Boolean,
) -> IO<Unit>
