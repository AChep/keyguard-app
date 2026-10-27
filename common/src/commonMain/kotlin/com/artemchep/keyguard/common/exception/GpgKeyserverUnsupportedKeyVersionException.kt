package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * The keyserver cannot store or return V6 keys, so a lookup of a V6
 * fingerprint cannot tell whether the key is published.
 */
class GpgKeyserverUnsupportedKeyVersionException : UnsupportedOperationException(
    "The keyserver does not support V6 GPG keys.",
), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.gpg_keyserver_error_v6_unsupported)
}
