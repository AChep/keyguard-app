package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleError
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PasswordStrength
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetPasswordStrength
import com.nulabinc.zxcvbn.Strength
import com.nulabinc.zxcvbn.Zxcvbn
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val PASSWORD_STRENGTH_VERSION = 2L

class GetPasswordStrengthJvm(
    private val wordlistService: WordlistService,
) : GetPasswordStrength {
    private val specialCharacterRegex = "[^a-zA-Z0-9]".toRegex()

    private val digitCharacterRegex = "[0-9]".toRegex()

    private val zxcvbn by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Zxcvbn()
    }

    constructor(directDI: DirectDI) : this(
        wordlistService = directDI.instance(),
    )

    override fun invoke(
        password: String,
    ): IO<PasswordStrength> = ioEffect(Dispatchers.Default) {
        val isPassphrase = passphraseStrengthIo(password)
            .handleError { null }
            .bind()
        if (isPassphrase != null) {
            return@ioEffect isPassphrase
        }

        val result = zxcvbn.measure(password)
        result.toDomain()
    }

    private fun passphraseStrengthIo(
        password: String,
    ): IO<PasswordStrength?> = ioEffect(Dispatchers.Default) {
        val parts = password
            .split(specialCharacterRegex)
            .filter { it.isNotEmpty() }
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
