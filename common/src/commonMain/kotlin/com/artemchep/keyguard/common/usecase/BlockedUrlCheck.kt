package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret

interface BlockedUrlCheck : (
    DGlobalUrlBlock,
    String,
    DSecret.Uri.MatchType,
    EquivalentDomains,
) -> IO<Boolean>
