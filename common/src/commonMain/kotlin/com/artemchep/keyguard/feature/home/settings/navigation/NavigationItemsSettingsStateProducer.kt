package com.artemchep.keyguard.feature.home.settings.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Route
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.common.usecase.GetPersistedNavItemsConfig
import com.artemchep.keyguard.common.usecase.PutNavItemsConfig
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.createConfirmationDialogIntent
import com.artemchep.keyguard.feature.home.navigation.createBuiltInHomeNavigationItem
import com.artemchep.keyguard.feature.home.navigation.normalizeHomeNavigationConfig
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceNavigationItemsSettingsState(): Loadable<NavigationItemsSettingsState> =
    with(localDI().direct) {
        produceNavigationItemsSettingsState(
            getNavItemsConfig = instance(),
            getPersistedNavItemsConfig = instance(),
            putNavItemsConfig = instance(),
            getCipherFilters = instance(),
            confirmationRouteFactory = instance(),
        )
    }

@Composable
fun produceNavigationItemsSettingsState(
    getNavItemsConfig: GetNavItemsConfig,
    getPersistedNavItemsConfig: GetPersistedNavItemsConfig,
    putNavItemsConfig: PutNavItemsConfig,
    getCipherFilters: GetCipherFilters,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Loadable<NavigationItemsSettingsState> = produceScreenState(
    key = "settings_navigation_items",
    initial = Loadable.Loading,
    args = arrayOf(
        getNavItemsConfig,
        getPersistedNavItemsConfig,
        putNavItemsConfig,
        getCipherFilters,
        confirmationRouteFactory,
    ),
) {
    navigationItemsSettingsStateProducer(
        getNavItemsConfig = getNavItemsConfig,
        getPersistedNavItemsConfig = getPersistedNavItemsConfig,
        putNavItemsConfig = putNavItemsConfig,
        getCipherFilters = getCipherFilters,
        confirmationRouteFactory = confirmationRouteFactory,
    )
}

suspend fun RememberStateFlowScope.navigationItemsSettingsStateProducer(
    getNavItemsConfig: GetNavItemsConfig,
    getPersistedNavItemsConfig: GetPersistedNavItemsConfig,
    putNavItemsConfig: PutNavItemsConfig,
    getCipherFilters: GetCipherFilters,
    confirmationRouteFactory: ConfirmationRouteFactory,
): Flow<Loadable<NavigationItemsSettingsState>> {
    fun putConfig(
        config: NavItemsConfig,
    ) {
        putNavItemsConfig(config)
            .launchIn(appScope)
    }

    fun updateConfig(
        config: NavItemsConfig?,
        block: (NavItemsConfig) -> NavItemsConfig,
    ) {
        val normalizedConfig = normalizeHomeNavigationConfig(
            config = config,
        )
        val updatedConfig = block(normalizedConfig)
            .copy(version = NavItemsConfigDefaults.VERSION)
        putConfig(updatedConfig)
    }

    return combine(
        getPersistedNavItemsConfig(),
        getNavItemsConfig(),
        getCipherFilters(),
    ) { persistedConfig, effectiveConfig, cipherFilters ->
        val normalizedPersistedConfig = normalizeHomeNavigationConfig(persistedConfig)
        val cipherFiltersById = cipherFilters.associateBy { it.id }
        val configuredRefs = normalizedPersistedConfig.items
            .map { it.ref }
            .toSet()
        val persistedSpecsByRef = normalizedPersistedConfig.items
            .associateBy { it.ref }
        val resetConfirmationTitle = translate(
            Res.string.navigation_items_reset_confirmation_title,
        )
        val resetConfirmationMessage = translate(
            Res.string.navigation_items_reset_confirmation_text,
        )

        val items = effectiveConfig.items
            .mapIndexed { index, spec ->
                val canHide = spec.ref.isHideable()
                val persistedSpec = persistedSpecsByRef[spec.ref]
                    ?: spec
                val runtimeHidden = persistedSpec.visible && !spec.visible
                val metadata = metadataOf(
                    spec = spec,
                    cipherFiltersById = cipherFiltersById,
                )
                NavigationItemsSettingsState.Item(
                    key = navItemRefKey(spec.ref),
                    ref = spec.ref,
                    title = metadata.title,
                    icon = metadata.icon,
                    subIcon = metadata.subIcon,
                    visible = spec.visible,
                    canMoveUp = index > 0,
                    canMoveDown = index < effectiveConfig.items.lastIndex,
                    canRemove = spec.ref is NavItemRef.CipherFilter,
                    onVisibilityToggle = if (canHide && !runtimeHidden) {
                        {
                            updateConfig(persistedConfig) { currentConfig ->
                                currentConfig.updateSpec(spec.ref) { item ->
                                    item.copy(
                                        visible = !item.visible,
                                    )
                                }
                            }
                        }
                    } else {
                        null
                    },
                    onMoveUp = if (index > 0) {
                        {
                            updateConfig(persistedConfig) { currentConfig ->
                                currentConfig.moveSpec(spec.ref, -1)
                            }
                        }
                    } else {
                        null
                    },
                    onMoveDown = if (index < effectiveConfig.items.lastIndex) {
                        {
                            updateConfig(persistedConfig) { currentConfig ->
                                currentConfig.moveSpec(spec.ref, 1)
                            }
                        }
                    } else {
                        null
                    },
                    onRemove = if (spec.ref is NavItemRef.CipherFilter) {
                        {
                            updateConfig(persistedConfig) { currentConfig ->
                                currentConfig.copy(
                                    items = currentConfig.items
                                        .filter { item -> item.ref != spec.ref },
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
            .toPersistentList()

        val builtInAvailableItems = NavItemsConfigDefaults.builtInKeys
            .asSequence()
            .map { key -> NavItemRef.BuiltIn(key) }
            .filter { ref -> ref !in configuredRefs }
            .mapNotNull { ref ->
                val spec = NavItemSpec(ref)
                val metadata = metadataOf(
                    spec = spec,
                    cipherFiltersById = cipherFiltersById,
                )
                NavigationItemsSettingsState.AvailableItem(
                    key = navItemRefKey(ref),
                    ref = ref,
                    title = metadata.title,
                    text = metadata.text,
                    icon = metadata.icon,
                    onAdd = {
                        updateConfig(persistedConfig) { currentConfig ->
                            currentConfig.copy(
                                items = currentConfig.items + spec,
                            )
                        }
                    },
                )
            }

        val customFilterAvailableItems = cipherFilters
            .asSequence()
            .map { filter -> NavItemRef.CipherFilter(filter.id) to filter }
            .filter { (ref, _) -> ref !in configuredRefs }
            .map { (ref, filter) ->
                val spec = NavItemSpec(ref)
                val metadata = metadataOf(
                    spec = spec,
                    cipherFiltersById = cipherFiltersById,
                )
                NavigationItemsSettingsState.AvailableItem(
                    key = navItemRefKey(ref),
                    ref = ref,
                    title = metadata.title,
                    text = metadata.text,
                    icon = filter.icon ?: metadata.icon,
                    onAdd = {
                        updateConfig(persistedConfig) { currentConfig ->
                            currentConfig.copy(
                                items = currentConfig.items + spec,
                            )
                        }
                    },
                )
            }

        NavigationItemsSettingsState(
            items = items,
            availableItems = (builtInAvailableItems + customFilterAvailableItems)
                .toList()
                .toPersistentList(),
            onReorder = { refs ->
                updateConfig(persistedConfig) { currentConfig ->
                    val remainingSpecs = currentConfig.items
                        .associateBy { it.ref }
                        .toMutableMap()
                    val reorderedSpecs = refs.mapNotNull { ref ->
                        remainingSpecs.remove(ref)
                    }
                    currentConfig.copy(
                        items = reorderedSpecs + remainingSpecs.values,
                    )
                }
            },
            onReset = {
                val intent = createConfirmationDialogIntent(
                    confirmationRouteFactory = confirmationRouteFactory,
                    title = resetConfirmationTitle,
                    message = resetConfirmationMessage,
                ) {
                    putConfig(
                        NavItemsConfigDefaults.defaultConfig(),
                    )
                }
                navigate(intent)
            },
        )
    }
        .map { state ->
            Loadable.Ok(state)
        }
        .stateIn(screenScope)
}

private data class NavigationItemMetadata(
    val title: TextHolder,
    val text: TextHolder?,
    val icon: ImageVector,
    val subIcon: ImageVector?,
)

private fun metadataOf(
    spec: NavItemSpec,
    cipherFiltersById: Map<String, DCipherFilter>,
): NavigationItemMetadata = when (val ref = spec.ref) {
    is NavItemRef.BuiltIn -> {
        val item = createBuiltInHomeNavigationItem(spec)
        NavigationItemMetadata(
            title = item?.label ?: TextHolder.Value(ref.key),
            text = TextHolder.Res(Res.string.navigation_items_built_in_text),
            icon = item?.icon ?: Icons.Outlined.Home,
            subIcon = null,
        )
    }

    is NavItemRef.CipherFilter -> {
        val filter = cipherFiltersById[ref.id]
        NavigationItemMetadata(
            title = filter
                ?.name
                ?.let(TextHolder::Value)
                ?: TextHolder.Res(Res.string.navigation_items_missing_filter_title),
            text = TextHolder.Res(
                if (filter != null) {
                    Res.string.navigation_items_custom_filter_text
                } else {
                    Res.string.navigation_items_missing_filter_text
                },
            ),
            icon = filter?.icon ?: Icons.Outlined.KeyguardCipherFilter,
            subIcon = if (filter?.icon != null) {
                Icons.Outlined.KeyguardCipherFilter
            } else null,
        )
    }

    is NavItemRef.PredefinedRoute -> NavigationItemMetadata(
        title = TextHolder.Value(ref.key),
        text = TextHolder.Res(Res.string.navigation_items_predefined_route_text),
        icon = Icons.Outlined.Home,
        subIcon = Icons.Outlined.Route,
    )
}

private fun NavItemsConfig.updateSpec(
    ref: NavItemRef,
    block: (NavItemSpec) -> NavItemSpec,
): NavItemsConfig {
    if (items.none { it.ref == ref }) {
        return this
    }
    return copy(
        items = items.map { item ->
            if (item.ref == ref) {
                block(item)
            } else {
                item
            }
        },
    )
}

private fun NavItemsConfig.moveSpec(
    ref: NavItemRef,
    offset: Int,
): NavItemsConfig {
    val fromIndex = items.indexOfFirst { it.ref == ref }
    if (fromIndex < 0) {
        return this
    }
    return copy(
        items = items.move(
            fromIndex = fromIndex,
            toIndex = fromIndex + offset,
        ),
    )
}

private fun <T> List<T>.move(
    fromIndex: Int,
    toIndex: Int,
): List<T> {
    if (fromIndex !in indices || toIndex !in indices || fromIndex == toIndex) {
        return this
    }
    return toMutableList()
        .apply {
            add(toIndex, removeAt(fromIndex))
        }
}

private fun navItemRefKey(
    ref: NavItemRef,
): String = when (ref) {
    is NavItemRef.BuiltIn -> "built_in:${ref.key}"
    is NavItemRef.CipherFilter -> "cipher_filter:${ref.id}"
    is NavItemRef.PredefinedRoute -> "predefined_route:${ref.key}"
}

private fun NavItemRef.isHideable(): Boolean = when (this) {
    is NavItemRef.BuiltIn -> when (key) {
        NavItemsConfigDefaults.BUILT_IN_SETTINGS,
        NavItemsConfigDefaults.BUILT_IN_VAULT -> false

        else -> true
    }

    is NavItemRef.CipherFilter -> true
    is NavItemRef.PredefinedRoute -> true
}
