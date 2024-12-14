package com.artemchep.keyguard.common.usecase.impl

import android.util.Log
import arrow.core.getOrElse
import arrow.optics.Getter
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.measure
import com.artemchep.keyguard.common.io.parallelSearch
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.LinkInfoAndroid
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.common.model.contains
import com.artemchep.keyguard.common.service.extract.LinkInfoExtractor
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetSuggestions
import com.artemchep.keyguard.feature.home.vault.search.findAlike
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.kodein.di.DirectDI
import org.kodein.di.allInstances
import org.kodein.di.instance

private const val ignoreLength = 3

private val ignoreWords = setOf(
    "www",
    // popular domains
    "com",
    "tk",
    "cn",
    "de",
    "net",
    "uk",
    "org",
    "nl",
    "icu",
    "ru",
    "eu",
    // rising domains
    "icu",
    "top",
    "xyz",
    "site",
    "online",
    "club",
    "wang",
    "vip",
    "shop",
    "work",
    // common words used in domain names
    "login",
    "signin",
    "auth",
    "dev",
    "debug",
    "release",
    // top 100 english words that are > ignoreLength
    "there",
    "some",
    "than",
    "this",
    "would",
    "first",
    "have",
    "each",
    "make",
    "water",
    "from",
    "which",
    "like",
    "been",
    "call",
    "into",
    "time",
    "that",
    "their",
    "word",
    "look",
    "now",
    "will",
    "find",
    "more",
    "long",
    "what",
    "other",
    "write",
    "down",
    "about",
    "were",
    "many",
    "number",
    "with",
    "when",
    "then",
    "come",
    "your",
    "them",
    "made",
    "they",
    "these",
    "could",
    "said",
    "people",
    "part",
)

private const val scoreThreshold = 0.1f

class GetSuggestionsImpl(
    private val androidExtractors: List<LinkInfoExtractor<LinkInfoPlatform.Android, LinkInfoAndroid>>,
    private val getAutofillDefaultMatchDetection: GetAutofillDefaultMatchDetection,
    private val cipherUrlCheck: CipherUrlCheck,
) : GetSuggestions<Any?> {
    constructor(directDI: DirectDI) : this(
        androidExtractors = directDI.allInstances(),
        getAutofillDefaultMatchDetection = directDI.instance(),
        cipherUrlCheck = directDI.instance(),
    )

    data class AutofillTargetAndroid(
        val appId: String,
        // It's preferable if we have the name, but we
        // can still operate without it.
        val appName: String?,
    ) {
        val appIdTokens = appId
            .lowercase()
            .split(".", "_")

        val appNameTokens = appName
            ?.lowercase()
            ?.split(" ")
    }

    data class AutofillTargetWeb(
        val webUrl: Url,
    ) {
        val webUrlTokens = webUrl.host
            .lowercase()
            .split(".", "_")
    }

    override fun invoke(
        ciphers: List<Any?>,
        getter: Getter<Any?, DSecret>,
        target: AutofillTarget,
        equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
    ): IO<List<Any?>> = ioEffect(Dispatchers.Default) {
        // Android app target
        val autofillTargetAndroid = kotlin.run {
            val link = target.links.firstNotNullOfOrNull { it as? LinkInfoPlatform.Android }
                ?: return@run null
            val appId = link.packageName
            val appInfo = androidExtractors
                .firstNotNullOfOrNull { extractor ->
                    if (extractor.from == LinkInfoPlatform.Android::class && extractor.handles(link)) {
                        extractor.extractInfo(link)
                            .attempt()
                            .bind()
                            .getOrNull()
                    } else {
                        null
                    }
                } as? LinkInfoAndroid.Installed
            AutofillTargetAndroid(
                appId = appId,
                appName = appInfo?.label,
            )
        }

        // Web target
        val autofillTargetWeb = kotlin.run {
            val link = target.links.firstNotNullOfOrNull { it as? LinkInfoPlatform.Web }
                ?: return@run null
            AutofillTargetWeb(
                webUrl = link.url,
            )
        }

        val equivalentDomainsHolder = equivalentDomainsBuilderFactory
            .build()
        val defaultMatchDetection = getAutofillDefaultMatchDetection()
            .first()
        val c = ciphers
            .parallelSearch { wrapper ->
                val cipher = getter.get(wrapper)
                val tokens = cipher.tokenize()
                val score = if (
                    AutofillHint.EMAIL_ADDRESS in target.hints ||
                    AutofillHint.PHONE_NUMBER in target.hints ||
                    AutofillHint.USERNAME in target.hints ||
                    AutofillHint.PASSWORD in target.hints ||
                    AutofillHint.APP_OTP in target.hints
                ) {
                    run {
                        val equivalentDomains = equivalentDomainsHolder
                            .getAndCache(cipher.accountId)
                        val scoreByWebDomainRaw = autofillTargetWeb
                            ?.findByWebDomain(
                                secret = cipher,
                                tokens = tokens,
                                cipherUrlCheck = cipherUrlCheck,
                                defaultMatchDetection = defaultMatchDetection,
                                equivalentDomains = equivalentDomains,
                            )
                        val scoreByWebDomain = scoreByWebDomainRaw
                            ?: 0f
                        if (scoreByWebDomain > scoreThreshold) {
                            // If we already have a score using the web info,
                            // then do not mix the browser application into it.
                            return@run scoreByWebDomain
                        }

                        val scoreByAppId = autofillTargetAndroid?.findByApp(cipher, tokens)
                            ?: 0f

                        val scaledScoreByWebDomain = scoreByWebDomain
                        val scaledScoreByAppId = if (scoreByWebDomainRaw != null) {
                            scoreByAppId / 30f
                        } else {
                            scoreByAppId
                        }
                        scaledScoreByWebDomain + scaledScoreByAppId
                    }
                } else if (
                    AutofillHint.CREDIT_CARD_NUMBER in target.hints ||
                    AutofillHint.CREDIT_CARD_EXPIRATION_DATE in target.hints ||
                    AutofillHint.CREDIT_CARD_SECURITY_CODE in target.hints
                ) {
                    1f
                } else {
                    0f
                }
//                if (score <= scoreThreshold) {
//                    return@parallelSearch null
//                }
                val favScore = if (cipher.favorite) {
                    1.1f
                } else {
                    1f
                }
                val correctingScore = if (target.hints.isNotEmpty()) {
                    val matchingCount = target.hints
                        .count {
                            cipher.contains(it)
                        }
                    (matchingCount.toFloat() / target.hints.size.toFloat())
                        .coerceAtLeast(0.4f)
                } else {
                    1f
                }
                (score * correctingScore * favScore) to wrapper
            }
        var avgSize = 0
        var avgScore = 0f
        val maxScore = c.maxByOrNull { it.first }?.first ?: 0f
        c.forEach { (score, _) ->
            if (score > scoreThreshold) {
                avgSize += 1
                avgScore += score
            }
        }
        avgScore /= avgSize.coerceAtLeast(1)
        val threshold =
            if (maxScore >= 10f) maxScore.div(3f).coerceAtLeast(10f) else avgScore / 1.5f
        Log.e("SuggestionsTest", "threshold $threshold")
        c
            .filter { (score, _) -> score >= threshold && score > scoreThreshold }
            .sortedByDescending { it.first }
            .also { list ->
                Log.e("SuggestionsTest", "-----")
                Log.e(
                    "SuggestionsTest",
                    "checked ${ciphers.size} ciphers, ${target.hints.size} hints",
                )
                Log.e("SuggestionsTest", "android=$autofillTargetAndroid")
                Log.e("SuggestionsTest", "web=$autofillTargetWeb")
                list.forEach { (score, model) ->
                    val cipher = getter.get(model)
                    Log.e(
                        "SuggestionsTest",
                        "[$score] ${cipher.name} | ${cipher.tokenize().joinToString { it }}",
                    )
                }
            }
            .map { it.second }
    }.measure { duration, anies ->
        Log.e("SuggestionsTest", "complete in $duration")
    }

    private val garbageRegex = "[.,!_:;&()\\[\\]]".toRegex()

    private fun DSecret.tokenize(): Set<String> {
        val out = mutableSetOf<String>()

        fun tryAdd(token: String) {
            if (token.length <= ignoreLength) {
                return
            }

            val processedToken = token.lowercase()
            if (processedToken in ignoreWords) {
                return
            }

            out += processedToken
        }

        // Split the name into tokens
        name.replace(garbageRegex, " ")
            .split(' ')
            .forEach(::tryAdd)
        // Extract domain name from the username
        // if that looks like an email.
        if (login?.username != null) {
            val tokens = login.username.split("@")
            if (tokens.size == 2) { // this is an email
                val domain = tokens[1]
                domain
                    .split('.')
                    .forEach(::tryAdd)
                // an email might contain extra info separated by the + sign
                val username = tokens[0]
                val extras = username.split('+')
                if (extras.size == 2) {
                    val extrasInfo = extras[1]
                    extrasInfo
                        .split('.')
                        .forEach(::tryAdd)
                }
            }
        }
        return out
    }
}

private fun GetSuggestionsImpl.AutofillTargetAndroid.findByApp(
    secret: DSecret,
    tokens: Set<String>,
): Float = run {
    // In case the host matches one of the URLs defined in
    // the cipher, then we give it maximum priority.
    val scoreByUri = secret.uris
        .any { uri ->
            uri.uri.startsWith("androidapp://") &&
                    uri.uri.trim().endsWith(appId) &&
                    uri.match != DSecret.Uri.MatchType.Never
        }
        .let { directMatch ->
            if (directMatch) 30f else 0f
        }
    val scoreByName = appNameTokens?.let {
        findAlike(
            tokens,
            it,
        )
    } ?: 0f
    val scoreById = findAlike(
        tokens,
        appIdTokens,
    )
    scoreById * 0.5f + scoreByName * 1.2f + scoreByUri
}

// If the URI matches something then the output score will be
// more or equal to 10, otherwise if the URI matches the content of the
// cipher the score it ~5 at max.
private suspend fun GetSuggestionsImpl.AutofillTargetWeb.findByWebDomain(
    secret: DSecret,
    tokens: Set<String>,
    cipherUrlCheck: CipherUrlCheck,
    defaultMatchDetection: DSecret.Uri.MatchType,
    equivalentDomains: EquivalentDomains,
): Float = run {
    val webHost = webUrl.host
    val webUrl = webUrl.toString()
    // In case the host matches one of the URLs defined in
    // the cipher, then we give it maximum priority.
    val scoreByUri = secret.uris
        .map { uri ->
            val match = cipherUrlCheck.invoke(uri, webUrl, defaultMatchDetection, equivalentDomains)
                .attempt()
                .bind()
                .getOrElse { false }
            if (match) {
                @Suppress("MoveVariableDeclarationIntoWhen")
                val matchType = uri.match ?: defaultMatchDetection
                when (matchType) {
                    DSecret.Uri.MatchType.Domain -> 10f
                    DSecret.Uri.MatchType.Host -> 20f
                    DSecret.Uri.MatchType.StartsWith -> 25f
                    DSecret.Uri.MatchType.Exact -> 25f
                    DSecret.Uri.MatchType.RegularExpression -> 30f
                    DSecret.Uri.MatchType.Never -> 0f
                }
            } else {
                0f
            }
        }
        .maxByOrNull { it }
        ?: 0f
    val scoreByRpId = secret.login?.fido2Credentials.orEmpty()
        .map { credential ->
            val match = isSubdomain(
                domain = credential.rpId,
                request = webHost,
            )
            if (match) {
                20f
            } else {
                0f
            }
        }
        .maxByOrNull { it }
        ?: 0f

    val scoreByHost = findAlike(
        tokens,
        webUrlTokens,
    )
    scoreByHost * 2f + scoreByUri + scoreByRpId
}
