package com.artemchep.keyguard.apple

import com.artemchep.keyguard.common.service.hibp.HIBP_API_TOKEN_LENGTH as SHARED_HIBP_API_TOKEN_LENGTH
import com.artemchep.keyguard.feature.remotepicker.KEEPASS_DATABASE_EXTENSION as SHARED_KEEPASS_DATABASE_EXTENSION

/** Re-exports shared constants to Swift, which can't see the top-level declarations of `:common`. */
object KeyguardConstants {
    const val HIBP_API_TOKEN_LENGTH = SHARED_HIBP_API_TOKEN_LENGTH
    const val KEEPASS_DATABASE_EXTENSION = SHARED_KEEPASS_DATABASE_EXTENSION
}
