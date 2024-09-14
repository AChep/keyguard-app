package com.artemchep.keyguard.common.service.totp.impl

import com.artemchep.keyguard.common.model.CryptoHashAlgorithm
import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base32Service
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.util.int
import com.artemchep.keyguard.common.util.millis
import kotlinx.datetime.Instant
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and
import kotlin.math.pow
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
    ): TotpCode = when (token) {
        is TotpToken.TotpAuth -> generateTotpAuthCode(
            token = token,
            timestamp = timestamp,
        )

        is TotpToken.HotpAuth -> generateHotpAuthCode(
            token = token,
        )

        is TotpToken.SteamAuth -> generateSteamAuthCode(
            token = token,
            timestamp = timestamp,
        )

        is TotpToken.MobileAuth -> generateMobileAuthCode(
            token = token,
            timestamp = timestamp,
        )
    }

    private fun roundToPeriodInSeconds(
        timestamp: Instant,
        period: Long,
    ): Long {
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
    ): TotpCode {
        val key = base32Service.decode(token.keyBase32)
        val period = token.period
        val time = roundToPeriodInSeconds(timestamp, period)
        // The resulting integer value of the code must have at most the required code
        // digits. Therefore the binary value is reduced by calculating the modulo
        // 10 ^ codeDigits.
        val codeInt = genHotpBinaryInt(
            key = key,
            counter = time,
            algorithm = token.algorithm,
        ).rem(10.0.pow(token.digits).toInt())
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
    ): TotpCode {
        val key = base32Service.decode(token.keyBase32)
        val counter = token.counter
        // The resulting integer value of the code must have at most the required code
        // digits. Therefore the binary value is reduced by calculating the modulo
        // 10 ^ codeDigits.
        val codeInt = genHotpBinaryInt(
            key = key,
            counter = counter,
            algorithm = token.algorithm,
        ).rem(10.0.pow(token.digits).toInt())
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
    ): TotpCode {
        val key = base32Service.decode(token.keyBase32)
        val period = token.period
        val time = roundToPeriodInSeconds(timestamp, period)

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
        val algorithmName = when (algorithm) {
            CryptoHashAlgorithm.SHA_1 -> "HmacSHA1"
            CryptoHashAlgorithm.SHA_256 -> "HmacSHA256"
            CryptoHashAlgorithm.SHA_512 -> "HmacSHA512"
            CryptoHashAlgorithm.MD5 -> "MD5"
        }
        val hash = Mac.getInstance(algorithmName).run {
            init(SecretKeySpec(key, "RAW")) // The hard-coded value 'RAW' is specified in the RFC
            doFinal(message)
        }

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
        // The resulting integer value of the code must have at most the required code
        // digits. Therefore the binary value is reduced by calculating the modulo
        // 10 ^ codeDigits.
        return binary.int
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun generateMobileAuthCode(
        token: TotpToken.MobileAuth,
        timestamp: Instant,
    ): TotpCode {
        val period = token.period
        // As per the spec, the mOTP code should be generated each 10 seconds and
        // valid for the next 3 minutes. This sucks for the users tho, as he does not know
        // the latter.
        val actualPeriod = 10L
        val multiplier = period / actualPeriod // must be recoverable
        val time = roundToPeriodInSeconds(timestamp, period) * multiplier

        // Generate a hash from the data.
        val data = buildString {
            append(time)
            append(token.secret)
            if (token.pin != null) {
                append(token.pin)
            }
        }
        val hash = cryptoGenerator.hashMd5(data.toByteArray())

        val code = hash
            .toHexString()
            .lowercase(Locale.ENGLISH)
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
}
