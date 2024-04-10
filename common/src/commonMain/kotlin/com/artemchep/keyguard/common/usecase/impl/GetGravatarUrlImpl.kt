package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GetGravatar
import com.artemchep.keyguard.common.usecase.GetGravatarUrl
import com.artemchep.keyguard.feature.favicon.GravatarUrl
import io.ktor.util.hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Locale

class GetGravatarUrlImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val getGravatar: GetGravatar,
) : GetGravatarUrl {
    private val emailPlusAddressingRegex = "\\+.+(?=@)".toRegex()

    class GravatarDisabledException : RuntimeException()

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        getGravatar = directDI.instance(),
    )

    override fun invoke(
        email: String,
    ): IO<GravatarUrl> = ioEffect(Dispatchers.Default) {
        val gravatarEnabled = getGravatar()
            .first()
        if (!gravatarEnabled) {
            throw GravatarDisabledException()
        }

        val emailHash = run {
            // https://en.gravatar.com/site/implement/hash/
            val sanitizedEmail = transformEmail(email)
            val sanitizedEmailBytes = sanitizedEmail.toByteArray()
            cryptoGenerator.hashMd5(sanitizedEmailBytes)
                .let(::hex)
        }
        val gravatarUrl = "https://www.gravatar.com/avatar/$emailHash?s=200&r=pg&d=404"
        GravatarUrl(gravatarUrl)
    }

    private fun transformEmail(email: String): String = email
        .trim()
        .replace(emailPlusAddressingRegex, "")
        .lowercase(Locale.ENGLISH)

}
