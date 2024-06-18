package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.dispatchOn
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.flatMap
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.artemchep.keyguard.platform.recordException
import com.nulabinc.zxcvbn.Strength
import com.nulabinc.zxcvbn.Zxcvbn
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

class GetPasswordStrengthJvm(
    private val logRepository: LogRepository,
    private val wordlistService: WordlistService,
) : GetPasswordStrength {
    companion object {
        private const val TAG = "GetPasswordStrength"
    }

    private val specialCharacterRegex = "[^a-zA-Z0-9]".toRegex()

    private val digitCharacterRegex = "[0-9]".toRegex()

    private val zxcvbn by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Zxcvbn()
    }

    // Computing a password strength is a fairly memory intensive
    // task. Limit parallelism to avoid hitting the memory limit and
    // being heavily throttled by the garbage collector.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(2)

    private class PasswordStrengthException(message: String) : RuntimeException(message)

    constructor(directDI: DirectDI) : this(
        logRepository = directDI.instance(),
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
            zxcvbn.measure(truncatedPassword).toDomain()
        }
        .timeout(PASSWORD_STRENGTH_TIMEOUT)
        .handleErrorTap { e ->
            // We can not just feed the original exception to the
            // analytics, because we have no idea how sanitized the
            // inputs of the underlying implementation are.
            val name = e::class.qualifiedName.orEmpty()
            val message = "Failed to calculate password strength for ${password.length} " +
                    "chars long password with a '$name' exception"
            recordException(PasswordStrengthException(message))
        }
        .dispatchOn(dispatcher)
        .measure { duration, passwordStrength ->
            logRepository.add(
                tag = TAG,
                message = "Calculating a password strength for ${password.length} chars long password " +
                        "took $duration, verdict ${passwordStrength.score}",
            )
        }

    private fun passphraseStrengthIo(
        password: String,
    ): IO<PasswordStrength?> = ioEffect {
        val parts = password
            .splitToSequence(specialCharacterRegex)
            .filter { it.isNotEmpty() }
            .toList()
        if (parts.size < 2) {
            return@ioEffect null
        }
        // Minimum length of the word from the passphrase dictionary
        // is 3 letters.
        if (parts.any { it.length < 3 }) {
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
            parts.size <= 3 -> 1000L
            parts.size <= 4 ->
                if (hasDigit) 1000000L else 100000L

            parts.size <= 5 ->
                if (hasDigit) 100000000000L else 1000000L

            parts.size <= 6 ->
                if (hasDigit) 100000000001L else 100000000000L

            else -> 100000000001L
        }
        PasswordStrength(
            crackTimeSeconds = crackTime,
            version = PASSWORD_STRENGTH_VERSION,
        )
    }
}

private fun Strength.toDomain(): PasswordStrength = PasswordStrength(
    crackTimeSeconds = crackTimeSeconds.offlineSlowHashing1e4perSecond.toLong(),
    version = PASSWORD_STRENGTH_VERSION,
)
