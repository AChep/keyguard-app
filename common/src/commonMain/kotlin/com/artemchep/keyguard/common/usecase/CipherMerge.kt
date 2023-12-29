package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret

interface CipherMerge : (List<DSecret>) -> DSecret
