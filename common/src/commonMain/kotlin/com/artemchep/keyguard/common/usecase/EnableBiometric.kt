package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.model.WithBiometric

interface EnableBiometric : (
    MasterSession.Key?,
) -> IO<WithBiometric>
