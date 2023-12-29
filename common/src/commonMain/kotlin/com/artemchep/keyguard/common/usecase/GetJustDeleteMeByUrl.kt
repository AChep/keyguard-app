package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo

interface GetJustDeleteMeByUrl : (String) -> IO<JustDeleteMeServiceInfo?>
