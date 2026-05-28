package com.artemchep.keyguard.common.service.totp.impl

import arrow.core.Either
import com.artemchep.keyguard.common.exception.OtpCodeGenerationException
import com.artemchep.keyguard.common.exception.OtpEmptySecretKeyException
import com.artemchep.keyguard.common.exception.OtpInvalidSecretKeyException
import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.millis
import com.artemchep.keyguard.common.util.toHex
import kotlin.time.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.experimental.and
import kotlin.math.roundToLong
import kotlin.time.Duration

// Code largely based on the
// https://github.com/marcelkliemannel/kotlin-onetimepassword
class TotpServiceImpl(
    private val base32Service: Base32Service,
    private val cryptoGenerator: CryptoGenerator,
) : TotpService {
    companion object {
        private const val STEAM_ALLOWED_CHARS = "23456789BCDFGHJKMNPQRTVWXY"
    }

    constructor(
        directDI: DirectDI,
    ) : this(
        base32Service = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun generate(
        token: TotpToken,
        timestamp: Instant,
        offset: Int,
    ): Either<Throwable, TotpCode> = Either
        .catch {
            generateOrThrow(
                token = token,
                timestamp = timestamp,
                offset = offset,
            )
        }
        .mapLeft(::mapException)

    private fun generateOrThrow(
        token: TotpToken,
        timestamp: Instant,
        offset: Int,
    ): TotpCode = when (token) {
        is TotpToken.TotpAuth -> generateTotpAuthCode(
            token = token,
            timestamp = timestamp,
            offset = offset,
        )

        is TotpToken.HotpAuth -> generateHotpAuthCode(
            token = token,
            offset = offset,
        )

        is TotpToken.SteamAuth -> generateSteamAuthCode(
            token = token,
            timestamp = timestamp,
            offset = offset,
        )

        is TotpToken.MobileAuth -> generateMobileAuthCode(
            token = token,
            timestamp = timestamp,
            offset = offset,
        )
    }

    private fun roundToPeriodInSeconds(
        timestamp: Instant,
        period: Long,
    ): Long {
        require(period > 0L) {
            "OTP period must be positive."
        }
        // Instead of `epochSeconds` round the current second. This is
        // to fix problems that occur if you request an update a few millis
        // before the next period.
        val seconds = (timestamp.millis.toDouble() / 1000.0).roundToLong()
        return seconds / period
    }

    /**
     * Generator for the RFC 4226 "HOTP: An HMAC-Based One-Time Password Algorithm"
     * (https://tools.ietf.org/html/rfc4226). TOTP is an extension on top of the HOTP
     * algorithm where the counter is bound to the current time.
     */
    private fun generateTotpAuthCode(
        token: TotpToken.TotpAuth,
        timestamp: Instant,
        offset: Int,
    ): TotpCode {
        val key = decodeSecretKey(token.keyBase32)
        val period = token.period
        val time = roundToPeriodInSeconds(timestamp, period) + offset
        val modulo = otpModulo(token.digits)
        // The resulting integer value of the code must have at most the required code
        // digits. Therefore the binary value is reduced by calculating the modulo
        // 10 ^ codeDigits.
        val codeInt = genHotpBinaryInt(
            key = key,
            counter = time,
            algorithm = token.algorithm,
        ).rem(modulo)
        // The integer code variable may contain a value with fewer digits than the
        // required code digits. Therefore the final code value is filled with zeros
        // on the left, till the code digits requirement is fulfilled.
        val code = codeInt.toString().padStart(token.digits, '0')
        return TotpCode(
            code = code,
            counter = TotpCode.TimeBasedCounter(
                timestamp = timestamp,
                expiration = kotlin.run {
                    // Get the beginning of the next period as an
                    // expiration date.
                    val exp = (time + 1L) * period
                    Instant.fromEpochSeconds(exp)
                },
                duration = with(Duration) { period.seconds },
            ),
        )
    }

    /**
     * Generator for the RFC 4226 "HOTP: An HMAC-Based One-Time Password Algorithm"
     * (https://tools.ietf.org/html/rfc4226)
     */
    private fun generateHotpAuthCode(
        token: TotpToken.HotpAuth,
        offset: Int,
    ): TotpCode {
        val key = decodeSecretKey(token.keyBase32)
        val counter = token.counter + offset
        val modulo = otpModulo(token.digits)
        // The resulting integer value of the code must have at most the required code
        // digits. Therefore the binary value is reduced by calculating the modulo
        // 10 ^ codeDigits.
        val codeInt = genHotpBinaryInt(
            key = key,
            counter = counter,
            algorithm = token.algorithm,
        ).rem(modulo)
        // The integer code variable may contain a value with fewer digits than the
        // required code digits. Therefore the final code value is filled with zeros
        // on the left, till the code digits requirement is fulfilled.
        val code = codeInt.toString().padStart(token.digits, '0')
        return TotpCode(
            code = code,
            counter = TotpCode.IncrementBasedCounter(
                counter = counter,
            ),
        )
    }

    /**
     * Generator for the Steam one-time-password schema. Yes, Steam the
     * app by Valve.
     */
    private fun generateSteamAuthCode(
        token: TotpToken.SteamAuth,
        timestamp: Instant,
        offset: Int,
    ): TotpCode {
        val key = decodeSecretKey(token.keyBase32)
        val period = token.period
        val time = roundToPeriodInSeconds(timestamp, period) + offset

        val binaryInt = genHotpBinaryInt(
            key = key,
            counter = time,
            algorithm = token.algorithm,
        )
        val code = buildString {
            var tmp = binaryInt
            for (i in 0 until token.digits) {
                val char = STEAM_ALLOWED_CHARS[tmp % STEAM_ALLOWED_CHARS.length]
                append(char)
                tmp /= STEAM_ALLOWED_CHARS.length
            }
        }
        return TotpCode(
            code = code,
            counter = TotpCode.TimeBasedCounter(
                timestamp = timestamp,
                expiration = kotlin.run {
                    // Get the beginning of the next period as an
                    // expiration date.
                    val exp = (time + 1L) * period
                    Instant.fromEpochSeconds(exp)
                },
                duration = with(Duration) { period.seconds },
            ),
        )
    }

    private fun genHotpBinaryInt(
        key: ByteArray,
        counter: Long,
        algorithm: CryptoHashAlgorithm,
    ): Int {
        // The counter value is the input parameter 'message' to the HMAC algorithm.
        // It must be represented by a byte array with the length of a long (8 bytes).
        val messageSize = 8
        val message = ByteArray(messageSize) { i ->
            val shift = (messageSize - i - 1).times(8)
            val byte = counter and (0xFF.toLong() shl shift) shr shift
            byte.toByte()
        }
        val hash = cryptoGenerator.hmac(
            key = key,
            data = message,
            algorithm = algorithm,
        )

        // The value of the offset is the lower 4 bits of the last byte of the hash
        // (0x0F = 0000 1111).
        val offset = hash.last().and(0x0F).toInt()
        // The first step for extracting the binary value is to collect the next four
        // bytes from the hash, starting at the index of the offset.
        val binary = ByteArray(4) { i ->
            hash[i + offset]
        }
        // The second step is to drop the most significant bit (MSB) from the first
        // step binary value (0x7F = 0111 1111).
        binary[0] = binary[0].and(0x7F)
        // The callers reduce this binary value by the requested digit count.
        return binary.int
    }

    private fun otpModulo(
        digits: Int,
    ): Int {
        require(digits in 1..9) {
            "OTP digits must be between 1 and 9."
        }

        var result = 1
        repeat(digits) {
            result *= 10
        }
        return result
    }

    private fun decodeSecretKey(
        keyBase32: String,
    ): ByteArray {
        if (keyBase32.isBlank()) {
            throw OtpEmptySecretKeyException()
        }

        val key = try {
            base32Service.decode(keyBase32)
        } catch (e: Throwable) {
            throw OtpInvalidSecretKeyException(e)
        }
        if (key.isEmpty()) {
            throw OtpInvalidSecretKeyException()
        }
        return key
    }

    private fun generateMobileAuthCode(
        token: TotpToken.MobileAuth,
        timestamp: Instant,
        offset: Int,
    ): TotpCode {
        val period = token.period
        // As per the spec, the mOTP code should be generated each 10 seconds and
        // valid for the next 3 minutes. This sucks for the users tho, as he does not know
        // the latter.
        val actualPeriod = 10L
        val multiplier = period / actualPeriod // must be recoverable
        val time = (roundToPeriodInSeconds(timestamp, period) + offset) * multiplier

        // Generate a hash from the data.
        val data = buildString {
            append(time)
            append(token.secret)
            if (token.pin != null) {
                append(token.pin)
            }
        }
        val hash = cryptoGenerator.hashMd5(data.encodeToByteArray())

        val code = hash
            .toHex()
            .take(token.digits)
        return TotpCode(
            code = code,
            counter = TotpCode.TimeBasedCounter(
                timestamp = timestamp,
                expiration = kotlin.run {
                    // Get the beginning of the next period as an
                    // expiration date.
                    val exp = (time / multiplier + 1L) * period
                    Instant.fromEpochSeconds(exp)
                },
                duration = with(Duration) { period.seconds },
            ),
        )
    }

    private fun mapException(
        e: Throwable,
    ): Throwable = when (e) {
        is OtpEmptySecretKeyException -> e
        is OtpInvalidSecretKeyException -> e
        is OtpCodeGenerationException -> e
        else -> OtpCodeGenerationException(e)
    }
}
