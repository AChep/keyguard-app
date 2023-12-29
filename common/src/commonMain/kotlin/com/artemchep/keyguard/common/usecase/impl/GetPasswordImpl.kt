package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPassword
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.security.SecureRandom
import kotlin.math.absoluteValue

class GetPasswordImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val getPassphrase: GetPassphrase,
) : GetPassword {
    private val secureRandom by lazy {
        SecureRandom()
    }

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        getPassphrase = directDI.instance(),
    )

    override fun invoke(
        context: GeneratorContext,
        config: PasswordGeneratorConfig,
    ): IO<String?> = when (config) {
        is PasswordGeneratorConfig.Password -> ioEffect(Dispatchers.Default) {
            if (
                config.length < 1 ||
                config.allChars.isEmpty()
            ) {
                null
            } else {
                val output = mutableListOf<Char>()

                var curUppercaseMin = 0L
                var curLowercaseMin = 0L
                var curNumbersMin = 0L
                var curSymbolsMin = 0L
                do {
                    var should = false
                    // uppercase
                    if (curUppercaseMin < config.uppercaseMin) {
                        curUppercaseMin++
                        should = true
                        // If possible, then add the symbol.
                        if (config.uppercaseChars.isNotEmpty()) {
                            output += config.uppercaseChars.random()
                        }
                    }
                    // lowercase
                    if (curLowercaseMin < config.lowercaseMin) {
                        curLowercaseMin++
                        should = true
                        // If possible, then add the symbol.
                        if (config.lowercaseChars.isNotEmpty()) {
                            output += config.lowercaseChars.random()
                        }
                    }
                    // numbers
                    if (curNumbersMin < config.numbersMin) {
                        curNumbersMin++
                        should = true
                        // If possible, then add the symbol.
                        if (config.numberChars.isNotEmpty()) {
                            output += config.numberChars.random()
                        }
                    }
                    // symbols
                    if (curSymbolsMin < config.symbolsMin) {
                        curSymbolsMin++
                        should = true
                        // If possible, then add the symbol.
                        if (config.symbolChars.isNotEmpty()) {
                            output += config.symbolChars.random()
                        }
                    }
                } while (should)

                repeat(config.length - output.size) {
                    output += config.allChars.random()
                }

                val r = output
                    .take(config.length)
                    .shuffled(secureRandom)
                    .toCharArray()
                String(r)
            }
        }

        is PasswordGeneratorConfig.Passphrase -> getPassphrase(config)
        is PasswordGeneratorConfig.EmailRelay -> {
            val cfg = config.config.data
            config.emailRelay.generate(context, cfg)
        }

        is PasswordGeneratorConfig.Composite -> invoke(context, config.config)
            .map { raw ->
                raw?.let { config.transform(it) }
            }
    }

    private fun <T> List<T>.random() = kotlin.run {
        val r = cryptoGenerator.random().absoluteValue
        this[r.rem(this.size)]
    }
}
