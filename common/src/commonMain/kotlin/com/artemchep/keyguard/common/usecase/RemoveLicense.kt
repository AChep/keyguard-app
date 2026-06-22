package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO

/**
 * License redeeming: removes the stored key and cached entitlement.
 */
interface RemoveLicense : () -> IO<Unit>
