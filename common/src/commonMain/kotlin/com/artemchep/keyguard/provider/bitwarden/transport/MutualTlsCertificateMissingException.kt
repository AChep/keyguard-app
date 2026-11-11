package com.artemchep.keyguard.provider.bitwarden.transport

import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.client_certificate_system_missing_note
import kotlinx.io.IOException

/**
 * The account's certificate is gone from the certificate store of the
 * system, or the app may no longer use it. The user has to choose it again.
 *
 * It is an [IOException], so an HTTP engine reports it as a failed request.
 */
class MutualTlsCertificateMissingException :
    IOException("The client certificate is not available on this device. Choose it again."),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.client_certificate_system_missing_note)
}
