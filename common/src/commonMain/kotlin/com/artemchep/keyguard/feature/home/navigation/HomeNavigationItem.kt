package com.artemchep.keyguard.feature.home.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.common.model.DCipherFilter
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemSpec
import com.artemchep.keyguard.common.model.NavItemsConfig
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.home.settings.SettingsRoute
import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.search.filter.FilterHolder
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.NavigationEntry
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.send.SendRoute
import com.artemchep.keyguard.feature.watchtower.WatchtowerRoute
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.customfilters_header_title
import com.artemchep.keyguard.res.home_generator_label
import com.artemchep.keyguard.res.home_send_label
import com.artemchep.keyguard.res.home_settings_label
import com.artemchep.keyguard.res.home_vault_label
import com.artemchep.keyguard.res.home_watchtower_label
import com.artemchep.keyguard.ui.icons.KeyguardCipherFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

const val HOME_NAV_VAULT_TEST_TAG = "nav_bar:vault"
const val HOME_NAV_SENDS_TEST_TAG = "nav_bar:sends"
const val HOME_NAV_GENERATOR_TEST_TAG = "nav_bar:generator"
const val HOME_NAV_WATCHTOWER_TEST_TAG = "nav_bar:watchtower"
const val HOME_NAV_SETTINGS_TEST_TAG = "nav_bar:settings"

private const val STACK_ID_VAULT = "vault"
private const val STACK_ID_SENDS = "sends"
private const val STACK_ID_GENERATOR = "generator"
private const val STACK_ID_WATCHTOWER = "watchtower"
private const val STACK_ID_SETTINGS = "settings"

private const val STACK_ID_CIPHER_FILTER_PREFIX = "cipher_filter:"

val homeVaultRoute = VaultRoute(
    args = VaultRoute.Args(
        main = true,
    ),
)

val homeSendsRoute = SendRoute()

val homeGeneratorRoute = GeneratorRoute(
    args = GeneratorRoute.Args(
        password = true,
        username = true,
        sshKey = true,
    ),
)

val homeWatchtowerRoute = WatchtowerRoute()

val homeSettingsRoute = SettingsRoute

data class HomeNavigationItem(
    val key: String,
    val spec: NavItemSpec,
    val testTag: String?,
    val route: Route,
    val stackId: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
    val label: TextHolder,
    val counterFlow: Flow<Int?> = flowOf(null),
) {
    fun isSelected(
        backStack: List<NavigationEntry>,
    ): Boolean {
        val rootRoute = backStack.firstOrNull()?.route
            ?: return false
        return matchesRootRoute(rootRoute)
    }

    private fun matchesRootRoute(
        rootRoute: Route,
    ): Boolean = when (spec.ref) {
        is NavItemRef.CipherFilter -> {
            val routeFilter = (route as? VaultRoute)?.args?.filter
            val rootFilter = (rootRoute as? VaultRoute)?.args?.filter
            routeFilter != null && routeFilter == rootFilter
        }

        else -> rootRoute.descriptor == route.descriptor
    }
}

fun resolveHomeNavigationItems(
    config: NavItemsConfig,
    cipherFilters: List<DCipherFilter>,
    watchtowerUnreadCount: Flow<Int?> = flowOf(null),
): List<HomeNavigationItem> {
    val cipherFiltersById = cipherFilters.associateBy { it.id }
    return config.items
        .asSequence()
        .filter { it.visible }
        .mapNotNull { spec ->
            when (val ref = spec.ref) {
                is NavItemRef.BuiltIn -> createBuiltInHomeNavigationItem(
                    spec = spec,
                    watchtowerUnreadCount = watchtowerUnreadCount,
                )

                is NavItemRef.CipherFilter -> cipherFiltersById[ref.id]
                    ?.let { filter ->
                        createCipherFilterHomeNavigationItem(
                            spec = spec,
                            filter = filter,
                        )
                    }

                is NavItemRef.PredefinedRoute -> null
            }
        }
        .toList()
}

fun createBuiltInHomeNavigationItem(
    spec: NavItemSpec,
    watchtowerUnreadCount: Flow<Int?> = flowOf(null),
): HomeNavigationItem? {
    val key = (spec.ref as? NavItemRef.BuiltIn)?.key
        ?: return null
    return when (key) {
        NavItemsConfigDefaults.BUILT_IN_VAULT -> HomeNavigationItem(
            key = key,
            spec = spec,
            testTag = HOME_NAV_VAULT_TEST_TAG,
            route = homeVaultRoute,
            stackId = STACK_ID_VAULT,
            icon = Icons.Outlined.Home,
            iconSelected = Icons.Filled.Home,
            label = TextHolder.Res(Res.string.home_vault_label),
        )

        NavItemsConfigDefaults.BUILT_IN_SENDS -> HomeNavigationItem(
            key = key,
            spec = spec,
            testTag = HOME_NAV_SENDS_TEST_TAG,
            route = homeSendsRoute,
            stackId = STACK_ID_SENDS,
            icon = Icons.AutoMirrored.Outlined.Send,
            iconSelected = Icons.AutoMirrored.Filled.Send,
            label = TextHolder.Res(Res.string.home_send_label),
        )

        NavItemsConfigDefaults.BUILT_IN_GENERATOR -> HomeNavigationItem(
            key = key,
            spec = spec,
            testTag = HOME_NAV_GENERATOR_TEST_TAG,
            route = homeGeneratorRoute,
            stackId = STACK_ID_GENERATOR,
            icon = Icons.Outlined.Password,
            iconSelected = Icons.Filled.Password,
            label = TextHolder.Res(Res.string.home_generator_label),
        )

        NavItemsConfigDefaults.BUILT_IN_WATCHTOWER -> HomeNavigationItem(
            key = key,
            spec = spec,
            testTag = HOME_NAV_WATCHTOWER_TEST_TAG,
            route = homeWatchtowerRoute,
            stackId = STACK_ID_WATCHTOWER,
            icon = Icons.Outlined.Security,
            iconSelected = Icons.Filled.Security,
            label = TextHolder.Res(Res.string.home_watchtower_label),
            counterFlow = watchtowerUnreadCount,
        )

        NavItemsConfigDefaults.BUILT_IN_SETTINGS -> HomeNavigationItem(
            key = key,
            spec = spec,
            testTag = HOME_NAV_SETTINGS_TEST_TAG,
            route = homeSettingsRoute,
            stackId = STACK_ID_SETTINGS,
            icon = Icons.Outlined.Settings,
            iconSelected = Icons.Filled.Settings,
            label = TextHolder.Res(Res.string.home_settings_label),
        )

        else -> null
    }
}

fun createCipherFilterHomeNavigationItem(
    spec: NavItemSpec,
    filter: DCipherFilter,
): HomeNavigationItem {
    val route = VaultRoute(
        args = VaultRoute.Args(
            appBar = VaultRoute.Args.AppBar(
                title = filter.name,
                subtitle = TextHolder.Res(Res.string.customfilters_header_title),
            ),
            filter = FilterHolder(filter.filter).filter,
            preselect = false,
            canAddSecrets = false,
        ),
    )
    val icon = filter.icon
        ?: Icons.Outlined.KeyguardCipherFilter
    return HomeNavigationItem(
        key = "cipher_filter:${filter.id}",
        spec = spec,
        testTag = null,
        route = route,
        stackId = "$STACK_ID_CIPHER_FILTER_PREFIX${filter.id}",
        icon = icon,
        iconSelected = icon,
        label = TextHolder.Value(filter.name),
    )
}
