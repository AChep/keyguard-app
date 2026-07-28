package com.artemchep.keyguard.detekt

import dev.detekt.api.RuleSetProvider
import org.junit.jupiter.api.Test
import java.util.ServiceLoader
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyguardRuleSetProviderTest {
    @Test
    fun `registers the keyguard rule set through ServiceLoader`() {
        val provider = ServiceLoader.load(
            RuleSetProvider::class.java,
            KeyguardRuleSetProvider::class.java.classLoader,
        ).firstOrNull { it is KeyguardRuleSetProvider }

        assertNotNull(provider, "KeyguardRuleSetProvider is not registered in META-INF/services")
        assertEquals("keyguard", provider.ruleSetId.value)
    }

    @Test
    fun `provides every mutablePersistedFlow rule`() {
        val ruleSet = KeyguardRuleSetProvider().instance()

        val rules = ruleSet.rules.keys.map { it.value }
        assertEquals(
            listOf(
                "MutablePersistedFlowDuplicateKey",
                "MutablePersistedFlowTypeSafety",
            ),
            rules.sorted(),
            "A rule that is not in the rule set never runs, whatever the config says.",
        )
    }
}
