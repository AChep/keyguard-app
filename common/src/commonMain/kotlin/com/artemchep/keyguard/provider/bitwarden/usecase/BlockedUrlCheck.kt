package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.BlockedUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class BlockedUrlCheckImpl(
    private val cipherUrlCheck: CipherUrlCheck,
) : BlockedUrlCheck {
    constructor(directDI: DirectDI) : this(
        cipherUrlCheck = directDI.instance(),
    )

    override fun invoke(
        uri: DGlobalUrlBlock,
        url: String,
        defaultMatchDetection: DSecret.Uri.MatchType,
        equivalentDomains: EquivalentDomains,
    ): IO<Boolean> = kotlin.run {
        val cipherUri = DSecret.Uri(
            uri = uri.uri,
            match = uri.mode.matchType,
        )
        cipherUrlCheck.invoke(
            cipherUri,
            url,
            defaultMatchDetection,
            equivalentDomains,
        )
    }
}
