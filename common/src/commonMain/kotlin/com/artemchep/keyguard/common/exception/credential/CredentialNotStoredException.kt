package com.artemchep.keyguard.common.exception.credential

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_credential_not_stored

/** The selected item was unavailable or could not store the credential. */
class CredentialNotStoredException : IllegalStateException("The item did not store the credential."),
    Readable {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_credential_not_stored)
}
