package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * A keyserver answered a fingerprint lookup with a key that has
 * another fingerprint.
 */
class GpgKeyserverFingerprintMismatchException : IllegalStateException(
    "Keyserver returned a public key with an unexpected fingerprint.",
), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.gpg_keyserver_error_unexpected_fingerprint)
}
