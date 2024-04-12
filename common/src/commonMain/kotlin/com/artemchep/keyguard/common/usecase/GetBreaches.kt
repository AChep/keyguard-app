package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup

interface GetBreaches : () -> IO<HibpBreachGroup>
