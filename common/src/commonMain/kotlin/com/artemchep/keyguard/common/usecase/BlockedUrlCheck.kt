package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.EquivalentDomains
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.UrlBlockScope

interface BlockedUrlCheck : (
    DGlobalUrlBlock,
    String,
    DSecret.Uri.MatchType,
    EquivalentDomains,
) -> IO<Boolean>

/** Whether the rule, where it matches, stops autofill: an enabled rule of [UrlBlockScope.ALL]. */
val DGlobalUrlBlock.blocksFill: Boolean
    get() = enabled && scope == UrlBlockScope.ALL

/** Whether the rule, where it matches, stops the offer to save a login: any enabled rule. */
val DGlobalUrlBlock.blocksSave: Boolean
    get() = enabled

/** The enabled [rules] that match any of the [urls]; see [blocksFill] and [blocksSave] for what each one stops. */
suspend fun BlockedUrlCheck.matching(
    rules: Collection<DGlobalUrlBlock>,
    urls: Collection<String>,
    defaultMatchDetection: DSecret.Uri.MatchType,
    equivalentDomains: EquivalentDomains,
): List<DGlobalUrlBlock> = rules.filter { rule ->
    rule.enabled && urls.any { url ->
        invoke(rule, url, defaultMatchDetection, equivalentDomains).bind()
    }
}
