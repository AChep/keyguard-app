package com.artemchep.keyguard.common.model

import arrow.core.Either
import arrow.core.Either.Companion.catch
import arrow.core.flatten
import arrow.core.right
import io.ktor.http.Url

private const val PREFIX_OTP_AUTH = "otpauth://"
private const val PREFIX_OTP_STEAM = "steam://"
private const val PREFIX_OTP_MOBILE = "motp://"

sealed interface TotpToken {
    companion object {
        fun parse(
            url: String,
        ): Either<Throwable, TotpToken> = catch {
            when {
                url.startsWith(PREFIX_OTP_AUTH) -> parseOtpAuth(url)
                url.startsWith(PREFIX_OTP_STEAM) -> parseOtpSteam(url)
                url.startsWith(PREFIX_OTP_MOBILE) -> parseOtpMobile(url)
                else -> {
                    // By default we think that the url is a key and the token
                    // type is otp-auth.
                    TotpAuth.Builder()
                        .build(
                            keyBase32 = url,
                            raw = url,
                        )
                        .right()
                }
            }
        }.flatten()
    }

    val raw: String
    val digits: Int

    data class TotpAuth(
        val algorithm: CryptoHashAlgorithm,
        val keyBase32: String,
        override val raw: String,
        override val digits: Int,
        val period: Long,
    ) : TotpToken {
        internal class Builder(
            var algorithm: CryptoHashAlgorithm = CryptoHashAlgorithm.SHA_1,
            var digits: Int = 6,
            var period: Long = 30L,
        ) {
            fun build(
                raw: String,
                keyBase32: String,
            ) = TotpAuth(
                algorithm = algorithm,
                digits = digits,
                period = period,
                keyBase32 = keyBase32,
                raw = raw,
            )
        }
    }

    data class HotpAuth(
        val algorithm: CryptoHashAlgorithm,
        val keyBase32: String,
        override val raw: String,
        override val digits: Int,
        val counter: Long,
    ) : TotpToken {
        internal class Builder(
            var algorithm: CryptoHashAlgorithm = CryptoHashAlgorithm.SHA_1,
            var digits: Int = 6,
            var counter: Long = 0L,
        ) {
            fun build(
                raw: String,
                keyBase32: String,
            ) = HotpAuth(
                algorithm = algorithm,
                digits = digits,
                counter = counter,
                keyBase32 = keyBase32,
                raw = raw,
            )
        }
    }

    data class SteamAuth(
        val algorithm: CryptoHashAlgorithm,
        val keyBase32: String,
        override val raw: String,
    ) : TotpToken {
        companion object {
            const val PERIOD = 30L
            const val DIGITS = 5
        }

        override val digits: Int get() = DIGITS

        val period: Long get() = PERIOD
    }

    data class MobileAuth(
        val issuer: String?,
        val username: String?,
        val secret: String,
        val pin: String?,
        override val raw: String,
    ) : TotpToken {
        companion object {
            const val PERIOD = 30L
            const val DIGITS = 6
        }

        override val digits: Int get() = DIGITS

        val period: Long get() = PERIOD
    }
}

private fun parseOtpAuth(
    url: String,
): Either<Throwable, TotpToken> = Either.catch {
    val parsedUrl = Url(url)
    when (parsedUrl.host) {
        "hotp" -> parseHotpAuth(url, parsedUrl)
        // totp
        else -> parseTotpAuth(url, parsedUrl)
    }
}.flatten()

private fun parseTotpAuth(
    raw: String,
    url: Url,
): Either<Throwable, TotpToken> = Either.catch {
    val builder = TotpToken.TotpAuth.Builder()
    var keyBase32 = ""

    // Parse the parameters of the otp auth
    val params = url.parameters
    params["digits"]?.also { digitsParam ->
        val n = digitsParam.toIntOrNull()
            ?: return@also
        if (n in 1..10) {
            builder.digits = n
        }
    }
    params["period"]?.also { periodParam ->
        val n = periodParam.toLongOrNull()
            ?: return@also
        if (n > 0L) {
            builder.period = n
        }
    }
    params["secret"]?.also { secretParam ->
        keyBase32 = secretParam
    }
    params["algorithm"]?.also { algorithmParam ->
        val alg = when (algorithmParam.lowercase()) {
            "sha1" -> CryptoHashAlgorithm.SHA_1
            "sha256" -> CryptoHashAlgorithm.SHA_256
            "sha512" -> CryptoHashAlgorithm.SHA_512
            else -> return@also
        }
        builder.algorithm = alg
    }

    if (keyBase32.isBlank()) {
        throw IllegalArgumentException("One time password key must not be empty.")
    }

    builder.build(
        keyBase32 = keyBase32,
        raw = raw,
    )
}

private fun parseHotpAuth(
    raw: String,
    url: Url,
): Either<Throwable, TotpToken> = Either.catch {
    val builder = TotpToken.HotpAuth.Builder()
    var keyBase32 = ""

    // Parse the parameters of the otp auth
    val params = url.parameters
    params["digits"]?.also { digitsParam ->
        val n = digitsParam.toIntOrNull()
            ?: return@also
        if (n in 1..10) {
            builder.digits = n
        }
    }
    params["counter"]?.also { counterParam ->
        val n = counterParam.toLongOrNull()
            ?: return@also
        builder.counter = n
    }
    params["secret"]?.also { secretParam ->
        keyBase32 = secretParam
    }
    params["algorithm"]?.also { algorithmParam ->
        val alg = when (algorithmParam.lowercase()) {
            "sha1" -> CryptoHashAlgorithm.SHA_1
            "sha256" -> CryptoHashAlgorithm.SHA_256
            "sha512" -> CryptoHashAlgorithm.SHA_512
            else -> return@also
        }
        builder.algorithm = alg
    }

    if (keyBase32.isBlank()) {
        throw IllegalArgumentException("One time password key must not be empty.")
    }

    builder.build(
        keyBase32 = keyBase32,
        raw = raw,
    )
}

private fun parseOtpSteam(
    url: String,
): Either<Throwable, TotpToken.SteamAuth> = Either.catch {
    val keyBase32 = url.substring(PREFIX_OTP_STEAM.length)
    TotpToken.SteamAuth(
        algorithm = CryptoHashAlgorithm.SHA_1,
        keyBase32 = keyBase32,
        raw = url,
    )
}

// See:
// https://motp.sourceforge.net/#1.1
//
// Example of the modified mOTP URI:
// motp://Google:artemchep?secret=haha&pin=1234
private fun parseOtpMobile(
    url: String,
): Either<Throwable, TotpToken.MobileAuth> = Either.catch {
    val data = url.substring(PREFIX_OTP_MOBILE.length)
    val (issuer, username, params) = kotlin.run {
        val parts = data.split('?')
        if (parts.size != 2) {
            throw IllegalArgumentException("URI is not a valid mOTP")
        }

        val host = parts[0].split(':')
        // First part is the issuer, second part of the host is the
        // username. Both of these can be empty.
        val issuer = host[0]
            .takeIf { it.isNotEmpty() }
        val username = host.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

        val params = kotlin.run {
            val fakeUrlString = "https://google.com?" + parts[1]
            val fakeUrl = Url(fakeUrlString)
            fakeUrl.parameters
        }
        Triple(
            issuer,
            username,
            params,
        )
    }

    val secret = requireNotNull(params["secret"]) {
        "URI must include the mOTP secret"
    }
    val pin = params["pin"]
    TotpToken.MobileAuth(
        issuer = issuer,
        username = username,
        secret = secret,
        pin = pin,
        raw = url,
    )
}
