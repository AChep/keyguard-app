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
}

private fun generateHashPhrase(
    hash: ByteArray,
    wordList: List<String>,
    minimumEntropy: Int = 64,
): List<String> {
    val wordsCount = wordList.size

    val entropyPerWord = ln(wordsCount.toDouble()) / ln(2.0)
    val entropyAvailable = hash.size * Byte.SIZE_BITS
    val remainingWordsCount = ceil(minimumEntropy / entropyPerWord).toInt()
    if (remainingWordsCount * entropyPerWord > entropyAvailable) {
        throw IllegalStateException("Output entropy of hash function is too small")
    }

    val phrase = ArrayList<String>(remainingWordsCount)
    val hashDigits = hash
        .map { it.toInt() and 0xFF }
        .toIntArray()
    repeat(remainingWordsCount) {
        val remainder = divideUnsignedBigEndianNumberInPlace(
            digits = hashDigits,
            divisor = wordsCount,
        )
        phrase += wordList[remainder]
    }
    return phrase
}

private fun divideUnsignedBigEndianNumberInPlace(
    digits: IntArray,
    divisor: Int,
): Int {
    var remainder = 0
    for (i in digits.indices) {
        val dividend = remainder * 256 + digits[i]
        digits[i] = dividend / divisor
        remainder = dividend % divisor
    }
    return remainder
}
