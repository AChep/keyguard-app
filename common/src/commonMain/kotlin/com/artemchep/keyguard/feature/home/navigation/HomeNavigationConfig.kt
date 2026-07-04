package com.artemchep.keyguard.feature.home.navigation

import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults

fun normalizeHomeNavigationConfig(
    config: NavItemsConfig?,
): NavItemsConfig {
    if (config == null) {
        return NavItemsConfigDefaults.defaultConfig()
    }

    val items = mutableListOf<NavItemSpec>()
    val seen = mutableSetOf<NavItemRef>()
    config.items.forEach { item ->
        if (seen.add(item.ref)) {
            items += item.enforceHomeNavigationRules()
        }
    }

    NavItemsConfigDefaults.builtInKeys.forEach { key ->
        val ref = NavItemRef.BuiltIn(key)
        if (seen.add(ref)) {
            items += NavItemSpec(
                ref = ref,
                visible = true,
            )
        }
    }

    return config.copy(
        version = NavItemsConfigDefaults.VERSION,
        items = items,
    )
}

fun applyHomeNavigationAvailability(
    config: NavItemsConfig,
    availability: Map<NavItemRef, Boolean>,
): NavItemsConfig = config.copy(
    items = config.items
        .map { item ->
            val available = availability[item.ref]
                ?: true
            val availableItem = if (available) {
                item
            } else {
                item.copy(
                    visible = false,
                )
            }
            availableItem.enforceHomeNavigationRules()
        },
)

private fun NavItemSpec.enforceHomeNavigationRules(): NavItemSpec {
    val builtInRef = ref as? NavItemRef.BuiltIn
    return when (builtInRef?.key) {
        NavItemsConfigDefaults.BUILT_IN_SETTINGS,
        NavItemsConfigDefaults.BUILT_IN_VAULT -> copy(
            visible = true,
        )

        else -> this
    }
}
