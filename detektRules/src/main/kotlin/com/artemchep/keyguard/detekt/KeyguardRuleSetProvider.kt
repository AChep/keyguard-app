package com.artemchep.keyguard.detekt

import dev.detekt.api.RuleSet
import dev.detekt.api.RuleSetId
import dev.detekt.api.RuleSetProvider

class KeyguardRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("keyguard")

    override fun instance(): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            ::MutablePersistedFlowTypeSafety,
            ::MutablePersistedFlowDuplicateKey,
        ),
    )
}
