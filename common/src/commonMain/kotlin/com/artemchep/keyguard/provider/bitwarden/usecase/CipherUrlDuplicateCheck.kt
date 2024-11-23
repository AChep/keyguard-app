package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import org.kodein.di.DirectDI
import org.kodein.di.instance

/**
 * @author Artem Chepurnyi
 */
class CipherUrlDuplicateCheckImpl(
    private val cipherUrlCheck: CipherUrlCheck,
) : CipherUrlDuplicateCheck {
    constructor(directDI: DirectDI) : this(
        cipherUrlCheck = directDI.instance(),
    )

    override fun invoke(
        a: DSecret.Uri,
        b: DSecret.Uri,
        defaultMatchDetection: DSecret.Uri.MatchType,
    ): IO<DSecret.Uri?> {
        val aMatch = a.match ?: defaultMatchDetection
        val bMatch = b.match ?: defaultMatchDetection

        // If one of the URIs are set to never match then
        // ignore it, it can not be a duplicate.
        if (
            aMatch == DSecret.Uri.MatchType.Never ||
            bMatch == DSecret.Uri.MatchType.Never
        ) {
            return io(null)
        }

        // If both a RegEx, then the only thing we can do is
        // to check if they are equal.
        if (
            aMatch == DSecret.Uri.MatchType.RegularExpression &&
            bMatch == DSecret.Uri.MatchType.RegularExpression
        ) {
            val result = a.takeIf { it.uri == b.uri }
            return io(result)
        }

        return cipherUrlCheck(a, b.uri, defaultMatchDetection)
            .effectMap { areMatching ->
                a.takeIf { areMatching }
            }
    }

}
