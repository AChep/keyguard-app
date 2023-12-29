package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.feature.favicon.GravatarUrl

interface GetGravatarUrl : (String) -> IO<GravatarUrl>
