package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretBroadUrlGroup
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilder

interface CipherUrlBroadCheck :
        (List<DSecret>, DSecret.Uri.MatchType, EquivalentDomainsBuilder) -> IO<List<DSecretBroadUrlGroup>>
