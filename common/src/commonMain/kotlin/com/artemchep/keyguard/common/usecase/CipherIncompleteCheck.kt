package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret

interface CipherIncompleteCheck : (DSecret) -> Boolean
