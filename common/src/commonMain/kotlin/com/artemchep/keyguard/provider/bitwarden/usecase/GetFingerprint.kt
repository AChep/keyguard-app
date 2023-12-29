package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AccountId
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.service.wordlist.WordlistService
import com.artemchep.keyguard.common.usecase.GetFingerprint
import com.artemchep.keyguard.provider.bitwarden.repository.BitwardenProfileRepository
import com.artemchep.keyguard.provider.bitwarden.usecase.util.pbk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
    private val profileRepository: BitwardenProfileRepository,
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val wordlistService: WordlistService,
    private val dispatcher: CoroutineContext = Dispatchers.Default,
) : GetFingerprint {
    companion object {
        private const val TAG = "GetFingerprint.bitwarden"
    }

    constructor(directDI: DirectDI) : this(
        profileRepository = directDI.instance(),
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        wordlistService = directDI.instance(),
    )

    override fun invoke(
        accountId: AccountId,
    ): Flow<String> = profileRepository
        .getById(accountId)
        .map { profileOrNull ->
            val profile = requireNotNull(profileOrNull) { "Profile not found." }
            val wordList = wordlistService
                .get()
                .bind()

            val publicKeyHash = kotlin.run {
                val privateKey = base64Service.decode(profile.privateKeyBase64)
                val publicKey = pbk(privateKey)
                cryptoGenerator.hashSha256(publicKey)
            }
            val fingerprint = cryptoGenerator.hkdf(
                seed = publicKeyHash,
                info = profile.profileId.toByteArray(),
            )
            generateHashPhrase(
                hash = fingerprint,
                wordList = wordList,
            ).joinToString("-")
        }
        .flowOn(dispatcher)

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
