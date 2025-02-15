package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.feature.localization.TextHolder

interface ClearVaultSession : (TextHolder?) -> IO<Unit>
