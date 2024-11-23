package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret

interface PutAutofillDefaultMatchDetection : (DSecret.Uri.MatchType) -> IO<Unit>
