package com.artemchep.keyguard.provider.bitwarden.usecase

import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSecretDuplicateGroup
import com.artemchep.keyguard.common.model.DWatchtowerAlertType
import com.artemchep.keyguard.common.model.fileName
import com.artemchep.keyguard.common.model.fileSize
import com.artemchep.keyguard.common.model.ignores
import com.artemchep.keyguard.common.service.crypto.CryptoGenerator
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.similarity.SimilarityService
import com.artemchep.keyguard.common.service.text.Base64Service
import com.artemchep.keyguard.common.usecase.CipherDuplicatesCheck
import org.kodein.di.DirectDI
import org.kodein.di.instance
import kotlin.math.absoluteValue
import kotlin.time.measureTimedValue

/**
 * @author Artem Chepurnyi
 */
class CipherDuplicatesCheckImpl(
    private val cryptoGenerator: CryptoGenerator,
    private val base64Service: Base64Service,
    private val similarityService: SimilarityService,
    private val logRepository: LogRepository,
) : CipherDuplicatesCheck {
    companion object {
        private val TAG = "CipherDuplicatesCheck"

        private val REGEX_SYMBOLS = "\\W".toRegex()
        private val REGEX_DIGITS = "\\d".toRegex()

        // Find a schema of a URI string, for
        // example https:// or androidapp://
        private val REGEX_SCHEMA = "^.+://".toRegex()

        private val ignoredWords = setOf(
            // prepositions
            "about",
            "along",
            "amid",
            "among",
            "anti",
            "as",
            "at",
            "but",
            "by",
            "for",
            "from",
            "in",
            "into",
            "like",
            "minus",
            "near",
            "of",
            "off",
            "on",
            "onto",
            "over",
            "past",
            "per",
            "plus",
            "save",
            "since",
            "than",
            "to",
            "up",
            "upon",
            "via",
            "with",
            "within",
            "without",
        )
    }

    data class ProcessedSecret(
        val source: DSecret,
        val processed: DSecret,
    )

    constructor(directDI: DirectDI) : this(
        cryptoGenerator = directDI.instance(),
        base64Service = directDI.instance(),
        similarityService = directDI.instance(),
        logRepository = directDI.instance(),
    )

    override fun invoke(
        ciphers: List<DSecret>,
        sensitivity: CipherDuplicatesCheck.Sensitivity,
    ): List<DSecretDuplicateGroup> = measureTimedValue {
        val existingGroupIds = mutableSetOf<String>()
        val pCiphers = ciphers
            .filter { cipher ->
                !cipher.ignores(DWatchtowerAlertType.DUPLICATE)
            }
            .map { cipher ->
                processCipher(cipher)
            }
        // We only search duplicates in the same type group. We
        // assume that no duplicates have different types.
        val pCiphersByType = pCiphers
            .groupBy { it.processed.type }
        pCiphersByType
            .mapValues { (_, pCiphersGroup) ->
                val out = mutableListOf<DSecretDuplicateGroup>()
                val src = pCiphersGroup.toMutableList()
                for (i in src.lastIndex downTo 0) {
                    var avg: MutableList<Float>? = null
                    var group: MutableList<ProcessedSecret>? = null
                    val target = src.removeLastOrNull()
                        ?: continue
                    // Go over the rest of the items and
                    // compare the with the target item.
                    for (j in src.lastIndex downTo 0) {
                        val candidate = src.getOrNull(j)
                            ?: continue
                        val accuracy = compare2(
                            a = target,
                            b = candidate,
                        ).eval()
                        if (accuracy > sensitivity.threshold) {
                            if (avg == null) {
                                avg = mutableListOf<Float>()
                            }
                            avg.add(accuracy)
                            if (group == null) {
                                group = mutableListOf()
                                group.add(target)
                            }
                            group.add(candidate)
                            // remove the potential duplicate from a list
                            src.removeAt(j)
                        }
                    }
                    if (group != null && avg != null) {
                        val groupId = group
                            .joinToString(separator = "") { it.source.id }
                            .let {
                                val data = it.toByteArray()
                                val hash = cryptoGenerator.hashSha256(data)
                                base64Service.encodeToString(hash)
                            }
                        val finalGroupId = if (groupId in existingGroupIds) {
                            // We happen to have a collision, so
                            // resort to generating a completely random
                            // identifier.
                            cryptoGenerator.uuid()
                        } else {
                            groupId
                        }
                        existingGroupIds += finalGroupId

                        val finalAccuracy = avg.average().toFloat()
                        out += DSecretDuplicateGroup(
                            id = finalGroupId,
                            accuracy = finalAccuracy,
                            ciphers = group
                                .map { it.source },
                        )
                    }
                }
                out
            }
            .flatMap { it.value }
    }.let { timedValue ->
        val logMessage = "Found ${timedValue.value.size} duplicates in ${timedValue.duration}"
        logRepository.post(TAG, logMessage, LogLevel.INFO)

        timedValue.value
    }

    private fun Sequence<Float>.eval() = average().toFloat()

    private fun compare2(
        a: ProcessedSecret,
        b: ProcessedSecret,
    ): Sequence<Float> = sequence {
        compareStringsFuzzy(
            a = a.processed.name,
            b = b.processed.name,
            strategy = ComparisonStrategy.Fuzzy.TH,
            weight = 1f,
        ).let { yield(it) }
        compareStringsFuzzy(
            a = a.processed.notes,
            b = b.processed.notes,
            strategy = ComparisonStrategy.Fuzzy.TH,
            weight = if (a.processed.type == DSecret.Type.SecureNote) 2f else 0.6f,
            ifOneEmpty = if (a.processed.type == DSecret.Type.SecureNote) -2f else -0.2f,
        ).let { yield(it) }

        // Urls
        compareListsFuzzy(
            a = a.processed.uris,
            b = b.processed.uris,
        ) { a, b ->
            val scoreMax = 1f
            val scoreMin = -1f
            if (
                a.match == DSecret.Uri.MatchType.RegularExpression ||
                b.match == DSecret.Uri.MatchType.RegularExpression
            ) {
                if (a.uri == b.uri) scoreMax else scoreMin
            } else {
                if (a.uri == b.uri) {
                    scoreMax
                } else {
                    val aDomain = a.uri.substringAfter('.')
                    val bDomain = b.uri.substringAfter('.')
                    if (aDomain == bDomain) {
                        scoreMin / 2f
                    } else {
                        scoreMin
                    }
                }
            }
        }.times(3f).let { yield(it) }

        // Fields
        compareListsFuzzy(
            a = a.processed.fields,
            b = b.processed.fields,
        ) { a, b ->
            if (a == b) 1f else -1f
        }.times(3f).let { yield(it) }

        // Attachments
        compareListsFuzzy(
            a = a.processed.attachments,
            b = b.processed.attachments,
        ) { a, b ->
            val aFileName = a.fileName()
            val bFileName = b.fileName()
            val aFileSize = a.fileSize()
            val bFileSize = b.fileSize()
            if (aFileName == bFileName && aFileSize == bFileSize) 1f else -1f
        }.times(2f).let { yield(it) }

        // Login
        if (
            a.processed.login != null &&
            b.processed.login != null &&
            a.processed.type == DSecret.Type.Login
        ) {
            val aLogin = a.processed.login
            val bLogin = b.processed.login
            compareStringsFuzzy(
                a = aLogin.username,
                b = bLogin.username,
                // If username is not exactly matched, then we
                // give it a very low score.
                min = -6f,
                strategy = ComparisonStrategy.Exact,
                weight = 0.5f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aLogin.password,
                b = bLogin.password,
                min = -3f,
                strategy = ComparisonStrategy.Exact,
                weight = 1f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aLogin.totp?.raw,
                b = bLogin.totp?.raw,
                min = -4f,
                strategy = ComparisonStrategy.Exact,
                weight = 5f,
            ).let { yield(it) }
            // Passkeys
            compareListsFuzzy(
                a = aLogin.fido2Credentials.map { it.credentialId },
                b = bLogin.fido2Credentials.map { it.credentialId },
            ) { a, b ->
                if (a == b) 1f else -3f
            }.times(3f).let { yield(it) }
            return@sequence
        }

        // Card
        if (
            a.processed.card != null &&
            b.processed.card != null &&
            a.processed.type == DSecret.Type.Card
        ) {
            val aCard = a.processed.card
            val bCard = b.processed.card
            compareStringsFuzzy(
                a = aCard.number,
                b = bCard.number,
                strategy = ComparisonStrategy.Exact,
                weight = 5f,
            ).let { yield(it) }
            return@sequence
        }

        // Identity
        if (
            a.processed.identity != null &&
            b.processed.identity != null &&
            a.processed.type == DSecret.Type.Card
        ) {
            val aIdentity = a.processed.identity
            val bIdentity = b.processed.identity
            compareStringsFuzzy(
                a = aIdentity.title,
                b = bIdentity.title,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.firstName,
                b = bIdentity.firstName,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.middleName,
                b = bIdentity.middleName,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.lastName,
                b = bIdentity.lastName,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.email,
                b = bIdentity.email,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.phone,
                b = bIdentity.phone,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            compareStringsFuzzy(
                a = aIdentity.username,
                b = bIdentity.username,
                strategy = ComparisonStrategy.Exact,
                weight = 0.7f,
            ).let { yield(it) }
            return@sequence
        }
    }

    private sealed interface ComparisonStrategy {
        data object Exact : ComparisonStrategy

        data class Fuzzy(
            val threshold: Float = 0f,
        ) : ComparisonStrategy {
            companion object {
                val TH = Fuzzy(threshold = 0.9f)
            }
        }
    }

    private fun <T> compareListsFuzzy(
        a: List<T>,
        b: List<T>,
        areEqual: (T, T) -> Float,
    ): Float {
        val sizeScore = -(a.size - b.size).toFloat().absoluteValue * 0.2f / 3f
        // We want to start from a smaller list.
        val (s, l) = if (a.size > b.size) {
            b to a.toMutableList()
        } else {
            a to b.toMutableList()
        }
        if (s.isEmpty()) {
            return sizeScore
        }
        val contentScore = s
            .mapNotNull { x ->
                var index = -1
                var max = Float.MIN_VALUE
                l.forEachIndexed { i, y ->
                    val score = areEqual(x, y)
                    if (score > max || index == -1) {
                        index = i
                        max = score
                    }
                }
                if (index != -1) {
                    // Do not remove urls that did not match
                    // to the current url.
                    if (max > 0f) {
                        l.removeAt(index)
                    }
                    max
                } else {
                    null
                }
            }
            .average()
            .toFloat()
        return sizeScore + contentScore
    }

    private fun compareStringsFuzzy(
        a: String?,
        b: String?,
        strategy: ComparisonStrategy,
        weight: Float = 1f,
        min: Float = -weight,
        max: Float = +weight,
        // If one of the arguments are empty,
        // but the other one is not.
        ifOneEmpty: Float = -0.2f,
        // If both of the arguments are empty.
        ifBothEmpty: Float = 0.05f,
        ifExactMatch: Float = max,
    ): Float {
        val score = when {
            a == null || b == null || a.isBlank() || b.isBlank() ->
                if (a == b) ifBothEmpty else ifOneEmpty

            a == b -> ifExactMatch
            else -> kotlin.run fuzzy@{
                when (strategy) {
                    is ComparisonStrategy.Exact -> {
                        // Continue.
                    }

                    is ComparisonStrategy.Fuzzy -> {
                        // Do not compare strings fuzzily if
                        // they are too long, as it might take
                        // too much processing power.
                        val sumLength = a.length + b.length
                        if (sumLength <= 100) {
                            val score = similarityService.score(a, b)
                                .coerceIn(0f, 1f)
                            return@fuzzy if (score < strategy.threshold) {
                                min
                            } else {
                                val distance = max - min
                                score * distance - min
                            }
                        }
                    }
                }
                min
            }
        }.coerceIn(
            minimumValue = min,
            maximumValue = max,
        )
        return score
    }

    //
    // Pre-Processing
    //

    private fun processCipher(cipher: DSecret): ProcessedSecret {
        val processed = cipher.copy(
            name = processString(cipher.name)
                .replace("copy", "")
                .replace("clone", "")
                .replace("duplicate", "")
                // Get rid of the digits, because a name
                // "Hello world 2" might be a duplicate to
                // "Hello world".
                .replace(REGEX_DIGITS, ""),
            notes = processString(cipher.notes),
            uris = cipher.uris
                .map { uri ->
                    val isRegex = uri.match == DSecret.Uri.MatchType.RegularExpression
                    if (isRegex) return@map uri

                    // Leave only the host name
                    val processedUri = uri.uri
                        .replace(REGEX_SCHEMA, "")
                        .substringBefore(":")
                        .substringBefore("/")
                        .trim()
                    uri.copy(uri = processedUri)
                },
            fields = cipher.fields
                .map { field ->
                    val processedName = field.name?.let(::processString)
                    val processedValue = field.value?.trim()
                    field.copy(
                        name = processedName,
                        value = processedValue,
                    )
                },
            // types
            login = cipher.login?.let(::processLogin),
            card = cipher.card?.let(::processCard),
            identity = cipher.identity?.let(::processIdentity),
        )
        return ProcessedSecret(
            source = cipher,
            processed = processed,
        )
    }

    private fun processLogin(login: DSecret.Login): DSecret.Login {
        val processedUsername = login.username?.trim()
        return login.copy(
            username = processedUsername,
        )
    }

    private fun processCard(card: DSecret.Card): DSecret.Card {
        val processedNumber = card.number?.let(::processString)
        return card.copy(
            number = processedNumber,
        )
    }

    private fun processIdentity(identity: DSecret.Identity): DSecret.Identity {
        return identity.copy(
            title = identity.title?.let(::processString),
            firstName = identity.firstName?.let(::processString),
            middleName = identity.middleName?.let(::processString),
            lastName = identity.lastName?.let(::processString),
            address1 = identity.address1?.let(::processString),
            address2 = identity.address2?.let(::processString),
            address3 = identity.address3?.let(::processString),
            city = identity.city?.let(::processString),
            state = identity.state?.let(::processString),
            postalCode = identity.postalCode?.let(::processString),
            country = identity.country?.let(::processString),
            company = identity.company?.let(::processString),
            email = identity.email?.let(::processString),
            phone = identity.phone?.let(::processString),
            ssn = identity.ssn?.let(::processString),
            username = identity.username?.let(::processString),
            passportNumber = identity.passportNumber?.let(::processString),
            licenseNumber = identity.licenseNumber?.let(::processString),
        )
    }

    private fun processString(str: String) = str
        .lowercase()
        .split(" ")
        .filter { it !in ignoredWords }
        .joinToString(separator = "")
        .replace(REGEX_SYMBOLS, "")
}
