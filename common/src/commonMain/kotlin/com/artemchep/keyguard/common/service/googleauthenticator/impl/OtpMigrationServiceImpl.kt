package com.artemchep.keyguard.common.service.googleauthenticator.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.flatten
import com.artemchep.keyguard.common.service.googleauthenticator.util.OtpMigrationConst
import com.artemchep.keyguard.common.service.googleauthenticator.OtpMigrationService
import com.artemchep.keyguard.common.service.googleauthenticator.util.build
import com.artemchep.keyguard.common.service.googleauthenticator.util.OtpMigrationParser
import com.artemchep.keyguard.common.service.text.Base32Service
import org.kodein.di.DirectDI
import org.kodein.di.instance

class OtpMigrationServiceImpl(
    private val base32Service: Base32Service,
    private val otpMigrationParser: OtpMigrationParser,
) : OtpMigrationService {
    constructor(
        directDI: DirectDI,
    ) : this(
        base32Service = directDI.instance(),
        otpMigrationParser = directDI.instance(),
    )

    override fun handler(uri: String): OtpMigrationService? =
        this.takeIf { uri.startsWith(OtpMigrationConst.PREFIX) }

    override fun convert(
        uri: String,
    ): Either<Throwable, String> = Either.catch {
        otpMigrationParser.parse(uri)
            .flatMap {
                it.otpParameters
                    .first()
                    .build(
                        base32Service = base32Service,
                    )
            }
    }.flatten()
}