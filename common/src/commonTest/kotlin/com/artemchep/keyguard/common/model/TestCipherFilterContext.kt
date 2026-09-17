package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.common.service.logging.LogRepositoryBridge
import com.artemchep.keyguard.common.service.tld.TldService
import com.artemchep.keyguard.common.usecase.CheckPasswordSetLeak
import com.artemchep.keyguard.common.usecase.CipherBreachCheck
import com.artemchep.keyguard.common.usecase.CipherExpiringCheck
import com.artemchep.keyguard.common.usecase.CipherIncompleteCheck
import com.artemchep.keyguard.common.usecase.CipherSshKeyWeakCheck
import com.artemchep.keyguard.common.usecase.CipherUnsecureUrlCheck
import com.artemchep.keyguard.common.usecase.CipherUrlBroadCheck
import com.artemchep.keyguard.common.usecase.CipherUrlDuplicateCheck
import com.artemchep.keyguard.common.usecase.GetAutofillDefaultMatchDetection
import com.artemchep.keyguard.common.usecase.GetBreaches
import com.artemchep.keyguard.common.usecase.GetEquivalentDomains
import com.artemchep.keyguard.common.usecase.GetPasskeys
import com.artemchep.keyguard.common.usecase.GetTwoFa
import com.artemchep.keyguard.common.usecase.GetWatchtowerAlerts
import com.artemchep.keyguard.provider.bitwarden.entity.HibpBreachGroup
import kotlin.time.Instant

/** Pure filters must not resolve unrelated services; tests opt into only the services they use. */
internal fun testCipherFilterContext(
    checkPasswordSetLeak: CheckPasswordSetLeak = object : CheckPasswordSetLeak {
        override fun invoke(request: CheckPasswordSetLeakRequest): Nothing = unusedFilterService()
    },
    cipherSshKeyWeakCheck: CipherSshKeyWeakCheck = object : CipherSshKeyWeakCheck {
        override fun invoke(cipher: DSecret): Nothing = unusedFilterService()
    },
) = CipherFilterContext(
    checkPasswordSetLeak = checkPasswordSetLeak,
    cipherSshKeyWeakCheck = cipherSshKeyWeakCheck,
    getAutofillDefaultMatchDetection = object : GetAutofillDefaultMatchDetection {
        override fun invoke(): Nothing = unusedFilterService()
    },
    cipherBreachCheck = object : CipherBreachCheck {
        override fun invoke(
            cipher: DSecret,
            breaches: HibpBreachGroup,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomains,
        ): Nothing = unusedFilterService()
    },
    equivalentDomainsBuilderFactory = EquivalentDomainsBuilderFactory(
        logRepository = LogRepositoryBridge(emptyList()),
        getEquivalentDomains = object : GetEquivalentDomains {
            override fun invoke(): Nothing = unusedFilterService()
        },
    ),
    getBreaches = object : GetBreaches {
        override fun invoke(forceRefresh: Boolean): Nothing = unusedFilterService()
    },
    cipherIncompleteCheck = object : CipherIncompleteCheck {
        override fun invoke(cipher: DSecret): Nothing = unusedFilterService()
    },
    cipherExpiringCheck = object : CipherExpiringCheck {
        override fun invoke(cipher: DSecret, now: Instant): Nothing = unusedFilterService()
    },
    cipherUnsecureUrlCheck = object : CipherUnsecureUrlCheck {
        override fun invoke(url: String): Nothing = unusedFilterService()
    },
    tldService = object : TldService {
        override val version: String = "unused"
        override fun getDomainName(host: String): Nothing = unusedFilterService()
    },
    getTwoFa = object : GetTwoFa {
        override fun invoke(): Nothing = unusedFilterService()
    },
    getPasskeys = object : GetPasskeys {
        override fun invoke(): Nothing = unusedFilterService()
    },
    cipherUrlDuplicateCheck = object : CipherUrlDuplicateCheck {
        override fun invoke(
            first: DSecret.Uri,
            second: DSecret.Uri,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomains,
        ): Nothing = unusedFilterService()
    },
    cipherUrlBroadCheck = object : CipherUrlBroadCheck {
        override fun invoke(
            ciphers: List<DSecret>,
            matchType: DSecret.Uri.MatchType,
            equivalentDomains: EquivalentDomainsBuilder,
        ): Nothing = unusedFilterService()
    },
    getWatchtowerAlerts = object : GetWatchtowerAlerts {
        override fun invoke(): Nothing = unusedFilterService()
    },
)

private fun unusedFilterService(): Nothing = error("This filter must not use an unrelated service")
