package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret

interface CipherUrlCheck : (
    DSecret.Uri,
    String,
    DSecret.Uri.MatchType,
    EquivalentDomains,
) -> IO<Boolean>
