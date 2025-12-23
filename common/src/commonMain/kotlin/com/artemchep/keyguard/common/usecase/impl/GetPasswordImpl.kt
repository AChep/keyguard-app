package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.effectMap
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.KeyPairConfig
import com.artemchep.keyguard.common.model.PasswordGeneratorConfig
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.crypto.KeyPairGenerator
import com.artemchep.keyguard.common.usecase.GetPassphrase
import com.artemchep.keyguard.common.usecase.GetPassword
import com.artemchep.keyguard.common.usecase.GetPinCode
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.security.SecureRandom
import kotlin.math.absoluteValue


class GetPasswordImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val keyPairGenerator: KeyPairGenerator,
    private val getPassphrase: GetPassphrase,
    private val getPinCode: GetPinCode,
) : GetPassword {
    private val secureRandom by lazy {
        SecureRandom()
    }

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        keyPairGenerator = directDI.instance(),
        getPassphrase = directDI.instance(),
        getPinCode = directDI.instance(),
    )

    override fun invoke(
        context: GeneratorContext,
        config: PasswordGeneratorConfig,
    ): IO<GetPasswordResult?> = when (config) {
        is PasswordGeneratorConfig.Value -> ioEffect {
            GetPasswordResult.Value(
                value = config.data,
            )
        }

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
                val p = String(r)
                GetPasswordResult.Value(p)
            }
        }

        is PasswordGeneratorConfig.Passphrase -> {
            getPassphrase(config)
                .map { p ->
                    GetPasswordResult.Value(p)
                }
        }

        is PasswordGeneratorConfig.PinCode -> {
            getPinCode(config)
                .map { p ->
                    GetPasswordResult.Value(p)
                }
        }

        is PasswordGeneratorConfig.EmailRelay -> {
            val cfg = config.config.data
            config.emailRelay
                .generate(context, cfg)
                .map { p ->
                    GetPasswordResult.Value(p)
                }
        }

        is PasswordGeneratorConfig.KeyPair -> {
            ioEffect {
                when (val cfg = config.config) {
                    is KeyPairConfig.Rsa -> {
                        keyPairGenerator.rsa(
                            length = cfg.length,
                        )
                    }

                    is KeyPairConfig.Ed25519 -> {
                        keyPairGenerator.ed25519()
                    }
                }
            }
                .effectMap {
                    keyPairGenerator.populate(it)
                }
                .map { GetPasswordResult.AsyncKey(it) }
        }

        is PasswordGeneratorConfig.Composite -> invoke(context, config.config)
            .effectMap { raw ->
                when (raw) {
                    null -> null
                    is GetPasswordResult.Value -> {
                        val p = raw.value.let { config.transform(it) }
                        p?.let { GetPasswordResult.Value(it) }
                    }

                    is GetPasswordResult.AsyncKey -> {
                        throw IllegalArgumentException()
                    }
                }
            }
    }

    private fun <T> List<T>.random() = kotlin.run {
        val r = cryptoGenerator.random().absoluteValue
        this[r.rem(this.size)]
    }
}
