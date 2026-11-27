package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * Another change of the master password or its key derivation finished
 * first, so this one started from an outdated fingerprint. Nothing
 * was changed.
 */
class MasterPasswordChangedException :
    RuntimeException("The master password changed in the meantime"),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_master_password_changed_meanwhile)
}
