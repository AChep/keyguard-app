package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.CipherFieldSwitchToggleRequest

interface CipherFieldSwitchToggle : (
    Map<String, List<CipherFieldSwitchToggleRequest>>,
) -> IO<Unit>
