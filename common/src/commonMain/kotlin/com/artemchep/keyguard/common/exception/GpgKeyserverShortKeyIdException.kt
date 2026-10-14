package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/**
 * A search by a 32-bit short key ID. Short key IDs are not unique, and
 * keyservers no longer serve them.
 */
class GpgKeyserverShortKeyIdException : IllegalArgumentException(
    "Short GPG key IDs are not supported.",
), Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.gpg_keyserver_error_short_key_id_title)

    override val text: TextHolder
        get() = TextHolder.Res(Res.string.gpg_keyserver_error_short_key_id_text)
}
