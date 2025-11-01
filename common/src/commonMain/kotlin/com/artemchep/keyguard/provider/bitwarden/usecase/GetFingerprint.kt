package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.PrivateKey
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.provider.bitwarden.usecase.util.pbk
import kotlinx.coroutines.Dispatchers
import org.kodein.di.DirectDI
import org.kodein.di.instance
import java.math.BigInteger
import kotlin.coroutines.CoroutineContext
import kotlin.math.ceil
import kotlin.math.ln

/**
 * @author Artem Chepurnyi
 */
class GetFingerprintImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val wordlistService: WordlistService,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetFingerprint {
    companion object {
        private const val TAG = "GetFingerprint.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        wordlistService = directDI.instance(),
    )

    override fun invoke(
        privateKey: PrivateKey,
        profileId: String,
    ): IO<String> = ioEffect(dispatcher) {
        val wordList = wordlistService
            .get()
            .bind()

        val publicKeyHash = kotlin.run {
            val publicKey = pbk(privateKey.byteArray)
            cryptoGenerator.hashSha256(publicKey)
        }
        val fingerprint = cryptoGenerator.hkdf(
            seed = publicKeyHash,
            info = profileId.toByteArray(),
        )
        generateHashPhrase(
            hash = fingerprint,
            wordList = wordList,
        ).joinToString("-")
    }

    private fun generateHashPhrase(
        hash: ByteArray,
        wordList: List<String>,
        minimumEntropy: Int = 64,
    ): List<String> {
        val wordsCount = wordList.size
        val wordsCountBi = BigInteger.valueOf(wordsCount.toLong())

        val entropyPerWord = ln(wordsCount.toDouble()) / ln(2.0)
        val entropyAvailable = hash.size * 4
        var remainingWordsCount = ceil(minimumEntropy / entropyPerWord)
        if (remainingWordsCount * entropyPerWord > entropyAvailable) {
            throw IllegalStateException("Output entropy of hash function is too small")
        }

        val phrase = mutableListOf<String>()
        var hashNumber = BigInteger.ZERO
        hash.toList()
            .asReversed()
            .forEachIndexed { index, byte ->
                var v = BigInteger.valueOf(byte.toUByte().toLong())
                repeat(index) {
                    v = v.times(BigInteger.valueOf(256L))
                }
                hashNumber = hashNumber.plus(v)
            }
        while (remainingWordsCount-- > 0) {
            val remainder = hashNumber
                .remainder(wordsCountBi)
                .toInt()
            hashNumber = hashNumber.divide(wordsCountBi)
            phrase.add(wordList[remainder])
        }
        return phrase
    }
}
