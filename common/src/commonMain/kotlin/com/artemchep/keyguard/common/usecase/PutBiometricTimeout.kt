package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import kotlin.time.Duration

interface PutBiometricTimeout : (Duration?) -> IO<Unit>
