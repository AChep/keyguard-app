package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.feature.localization.TextHolder

interface ClearVaultSession : (LockReason, TextHolder) -> IO<Unit>
