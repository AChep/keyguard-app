package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret

interface CipherSshKeyWeakCheck : (DSecret) -> Boolean
