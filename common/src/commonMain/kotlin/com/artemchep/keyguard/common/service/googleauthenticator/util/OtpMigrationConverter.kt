package com.artemchep.keyguard.common.service.googleauthenticator.util

import arrow.core.Either
import com.artemchep.keyguard.common.service.googleauthenticator.model.OtpAuthMigrationData
import com.artemchep.keyguard.common.service.text.Base32Service
import io.ktor.http.*

/**
 * Builds a valid OTP URI for use with all other
 * than Google Authenticator apps.
 */
fun OtpAuthMigrationData.OtpParameters.build(
    base32Service: Base32Service,
): Either<Throwable, String> = Either.catch {
    when (type) {
        OtpAuthMigrationData.OtpParameters.Type.OTP_TYPE_TOTP -> buildTotp(base32Service)
        OtpAuthMigrationData.OtpParameters.Type.OTP_TYPE_HOTP -> buildHotp(base32Service)
        else -> throw IllegalArgumentException("Unsupported OTP type!")
    }
}

private val OtpAuthMigrationData.OtpParameters.DigitCount.count
    get() = when (this) {
        OtpAuthMigrationData.OtpParameters.DigitCount.DIGIT_COUNT_EIGHT -> 8
        OtpAuthMigrationData.OtpParameters.DigitCount.DIGIT_COUNT_SIX -> 6
        OtpAuthMigrationData.OtpParameters.DigitCount.DIGIT_COUNT_UNSPECIFIED -> null
    }

private val OtpAuthMigrationData.OtpParameters.Algorithm.str
    get() = when (this) {
        OtpAuthMigrationData.OtpParameters.Algorithm.ALGORITHM_SHA1 -> "sha1"
        OtpAuthMigrationData.OtpParameters.Algorithm.ALGORITHM_SHA256 -> "sha256"
        OtpAuthMigrationData.OtpParameters.Algorithm.ALGORITHM_SHA512 -> "sha512"
        OtpAuthMigrationData.OtpParameters.Algorithm.ALGORITHM_MD5 -> "md5"
        OtpAuthMigrationData.OtpParameters.Algorithm.ALGORITHM_UNSPECIFIED -> null
    }

private fun OtpAuthMigrationData.OtpParameters.buildTotp(
    base32Service: Base32Service,
): String = build(
    "totp",
    base32Service = base32Service,
) {
    // period
    parameters.append("period", "30")
}

private fun OtpAuthMigrationData.OtpParameters.buildHotp(
    base32Service: Base32Service,
): String = build(
    "hotp",
    base32Service = base32Service,
) {
    // counter
    val counter = counter
    if (counter != null) {
        parameters.append("counter", counter.toString())
    }
}

private fun OtpAuthMigrationData.OtpParameters.build(
    host: String,
    base32Service: Base32Service,
    builder: URLBuilder.() -> Unit,
): String {
    return URLBuilder("otpauth://$host/").apply {
        val path = issuer.orEmpty() + ":" + name.orEmpty()
        appendPathSegments(path)

        // digits
        val digits = digits.count
        if (digits != null) {
            parameters.append("digits", digits.toString())
        }

        // secret
        val secret = base32Service.encodeToString(secret)
        parameters.append("secret", secret)
        // algorithm
        val algorithm = algorithm.str
        if (algorithm != null) {
            parameters.append("algorithm", algorithm)
        }

        builder()
    }.buildString()
}
