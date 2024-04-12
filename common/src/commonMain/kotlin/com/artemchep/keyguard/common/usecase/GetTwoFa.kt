package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo

interface GetTwoFa : () -> IO<List<TwoFaServiceInfo>>
