package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.parallel
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetPassphrase
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.util.Locale

class PasswordGeneratorDiceware(
    private val wordlistService: WordlistService,
    private val cryptoGenerator: CryptoGenerator,
) : GetPassphrase {
    constructor(
        directDI: DirectDI,
    ) : this(
        wordlistService = directDI.instance(),
        cryptoGenerator = directDI.instance(),
    )

    override fun invoke(
        config: PasswordGeneratorConfig.Passphrase,
    ): IO<String> = kotlin.run {
        // Find which word should be replaced with a
        // user-defined custom word.
        val customWordIndex = cryptoGenerator.random(0 until config.length)
        List(config.length) {
            val generateWordIo = kotlin.run {
                val useCustomWord = customWordIndex == it &&
                        config.customWord != null
                if (useCustomWord) {
                    val customWord = config.customWord
                        .orEmpty()
                    io(customWord)
                } else {
                    val wordlist = config.wordlist
                        ?.takeIf { it.isNotEmpty() }
                    if (wordlist != null) {
                        ioEffect {
                            wordlist
                                .random()
                        }
                    } else
                    // Otherwise load from
                    // the default wordlist.
                        getWordIo()
                }
            }
            generateWordIo
                // capitalize
                .map { phrase ->
                    if (config.capitalize) {
                        phrase.capitalize(Locale.US)
                    } else {
                        phrase
                    }
                }
        }
            .parallel()
            // include number
            .map { phrases ->
                if (!config.includeNumber) {
                    return@map phrases
                }

                val targetIndex = cryptoGenerator.random(phrases.indices)
                phrases
                    .mapIndexed { index, phrase ->
                        if (targetIndex == index) {
                            val range = when (config.length) {
                                1 -> 1000..9999
                                2 -> 100..999
                                else -> 10..99
                            }
                            val number = cryptoGenerator.random(range)
                            phrase + number
                        } else {
                            phrase
                        }
                    }
            }
            .map {
                it.joinToString(separator = config.delimiter)
            }
    }

    private fun getWordIo() = wordlistService
        .get()
        .effectMap(Dispatchers.Default) { wordlist ->
            wordlist.random()
        }

    private fun <T> List<T>.random() = cryptoGenerator
        .random(this.indices)
        .let(this::get)
}
