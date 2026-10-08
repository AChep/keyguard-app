package com.artemchep.keyguard.apple

import com.artemchep.keyguard.platform.appleAppGroupContainerPath
import com.artemchep.keyguard.platform.prepareAppleStorage

/** Storage preflight that is safe to call from Swift before constructing KeyguardCore. */
object KeyguardStorage {
    /** Returns a localization key on failure; failed resolution is never cached. */
    fun prepare(): String? = prepareAppleStorage()

    fun sharedContainerPath(): String? = if (prepare() == null) appleAppGroupContainerPath() else null
}
