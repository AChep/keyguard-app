package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo

interface GetPasskeys : () -> IO<List<PassKeyServiceInfo>>
