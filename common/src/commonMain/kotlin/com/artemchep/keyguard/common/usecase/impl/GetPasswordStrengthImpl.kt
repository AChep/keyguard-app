package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.dispatchOn
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.platform.recordException
import com.artemchep.keyguard.util.zxcvbn.Zxcvbn
import com.artemchep.keyguard.util.zxcvbn.ZxcvbnResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val PASSWORD_STRENGTH_VERSION = 2L
private const val PASSWORD_STRENGTH_TIMEOUT = 5000L

// The longer a password is the longer it takes to
// calculate its strength. This causes an issue where
// a user can effectively block his own device by creating
// a huge password that will take ages to process.
private const val PASSWORD_LENGTH_UPPER_LIMIT = 32

// A passphrase must consist of at least this many words, each
// of them at least this long, to be recognized as one.
private const val PASSPHRASE_MIN_WORDS = 2
private const val PASSPHRASE_MIN_WORD_LENGTH = 3

private const val PASSPHRASE_WORDS_TIER_1 = 3
private const val PASSPHRASE_WORDS_TIER_2 = 4
private const val PASSPHRASE_WORDS_TIER_3 = 5
private const val PASSPHRASE_WORDS_TIER_4 = 6

private const val PASSPHRASE_CRACK_TIME_TIER_1 = 1000L
private const val PASSPHRASE_CRACK_TIME_TIER_2 = 100000L
private const val PASSPHRASE_CRACK_TIME_TIER_2_DIGITS = 1000000L
private const val PASSPHRASE_CRACK_TIME_TIER_3 = 1000000L
private const val PASSPHRASE_CRACK_TIME_TIER_3_DIGITS = 100000000000L
private const val PASSPHRASE_CRACK_TIME_TIER_4 = 100000000000L
private const val PASSPHRASE_CRACK_TIME_TIER_4_DIGITS = 100000000001L
private const val PASSPHRASE_CRACK_TIME_TIER_5 = 100000000001L

class GetPasswordStrengthImpl(
    private val wordlistService: WordlistService,
) : GetPasswordStrength {
    companion object {
        private const val TAG = "GetPasswordStrength"
    }

    private val specialCharacterRegex = "[^a-zA-Z0-9]".toRegex()

    private val digitCharacterRegex = "[0-9]".toRegex()

    // Computing a password strength is a fairly memory intensive
    // task. Limit parallelism to avoid hitting the memory limit and
    // being heavily throttled by the garbage collector.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)

    private class PasswordStrengthException(message: String) : RuntimeException(message)

    constructor(directDI: DirectDI) : this(
        wordlistService = directDI.instance(),
    )

    override fun invoke(
        password: String,
    ): IO<PasswordStrength> = io(password)
        .flatMap {
            passphraseStrengthIo(password)
                .handleError { null }
        }
        .effectMap { passphraseStrength ->
            if (passphraseStrength != null) {
                return@effectMap passphraseStrength
            }

            val truncatedPassword = password.take(PASSWORD_LENGTH_UPPER_LIMIT)
            Zxcvbn.estimate(truncatedPassword).toDomain()
        }
        .timeout(PASSWORD_STRENGTH_TIMEOUT)
        .handleErrorTap { e ->
            // We can not just feed the original exception to the
            // analytics, because we have no idea how sanitized the
            // inputs of the underlying implementation are.
            val name = e::class.qualifiedName.orEmpty()
            val message = "Failed to calculate password strength with a '$name' exception"
            recordException(PasswordStrengthException(message))
        }
        .dispatchOn(dispatcher)

    private fun passphraseStrengthIo(
        password: String,
    ): IO<PasswordStrength?> = ioEffect {
        val parts = password
            .splitToSequence(specialCharacterRegex)
            .filter { it.isNotEmpty() }
            .toList()
        if (parts.size < PASSPHRASE_MIN_WORDS) {
            return@ioEffect null
        }
        // Minimum length of the word from the passphrase dictionary
        // is 3 letters.
        if (parts.any { it.length < PASSPHRASE_MIN_WORD_LENGTH }) {
            return@ioEffect null
        }

        // Check the the first & last word matches
        // the ones from the dictionary.
        val wordList = wordlistService.get()
            .bind()

        fun inWordList(word: String) = word
            .replace(digitCharacterRegex, "")
            .lowercase() in wordList

        val hasDigit = password.contains(digitCharacterRegex)
        val isPassphrase = inWordList(parts.first()) &&
                inWordList(parts.last())
        if (!isPassphrase) {
            return@ioEffect null
        }

        val crackTime = when {
            parts.size <= PASSPHRASE_WORDS_TIER_1 -> PASSPHRASE_CRACK_TIME_TIER_1
            parts.size <= PASSPHRASE_WORDS_TIER_2 ->
                if (hasDigit) {
                    PASSPHRASE_CRACK_TIME_TIER_2_DIGITS
                } else {
                    PASSPHRASE_CRACK_TIME_TIER_2
                }

            parts.size <= PASSPHRASE_WORDS_TIER_3 ->
                if (hasDigit) {
                    PASSPHRASE_CRACK_TIME_TIER_3_DIGITS
                } else {
                    PASSPHRASE_CRACK_TIME_TIER_3
                }

            parts.size <= PASSPHRASE_WORDS_TIER_4 ->
                if (hasDigit) {
                    PASSPHRASE_CRACK_TIME_TIER_4_DIGITS
                } else {
                    PASSPHRASE_CRACK_TIME_TIER_4
                }

            else -> PASSPHRASE_CRACK_TIME_TIER_5
        }
        PasswordStrength(
            crackTimeSeconds = crackTime,
            version = PASSWORD_STRENGTH_VERSION,
        )
    }
}

private fun ZxcvbnResult.toDomain(): PasswordStrength = PasswordStrength(
    crackTimeSeconds = crackTimes.offlineSlowHashing1e4PerSecond.toLong(),
    version = PASSWORD_STRENGTH_VERSION,
)
