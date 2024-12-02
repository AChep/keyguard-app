package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup

interface CipherBreachCheck : (
    DSecret,
    HibpBreachGroup,
    DSecret.Uri.MatchType,
    EquivalentDomains,
) -> IO<Boolean>
