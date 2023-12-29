package com.artemchep.keyguard.common.usecase

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AccountId

interface PutAccountColorById : (
    Map<AccountId, Color>,
) -> IO<Unit>
