package com.artemchep.keyguard.common.service.googleauthenticator

import arrow.core.Either

interface OtpMigrationService {
    /**
     * Returns a migrations service to use, or
     * `null` if there no tool to use.
     */
    fun handler(uri: String): OtpMigrationService?

    fun convert(uri: String): Either<Throwable, String>
}
