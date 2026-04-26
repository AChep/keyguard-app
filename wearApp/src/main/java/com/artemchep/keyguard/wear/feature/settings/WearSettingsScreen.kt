package com.artemchep.keyguard.wear.feature.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.feature.home.settings.SettingsRouteListAction
import com.artemchep.keyguard.feature.home.settings.SettingsRouteListSection
import com.artemchep.keyguard.feature.home.settings.accounts.accountListScreenState
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountItem
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.rememberSettingsRouteListItems
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRouteFactory
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRouteFactory
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.settings_main_header_title
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.wear.feature.auth.WearLoginMethodRoute
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import org.jetbrains.compose.resources.stringResource
import kotlin.getValue
import org.kodein.di.compose.rememberInstance

@Composable
fun WearSettingsScreen() {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    val autofillSettingsRouteFactory by rememberInstance<AutofillSettingsRouteFactory>()
    val securitySettingsRouteFactory by rememberInstance<SecuritySettingsRouteFactory>()
    val uiSettingsRouteFactory by rememberInstance<UiSettingsRouteFactory>()
    val otherSettingsRouteFactory by rememberInstance<OtherSettingsRouteFactory>()

    val accountListStateWrapper = accountListScreenState(
        rootRouterName = null,
    )
    val accountListState = remember(accountListStateWrapper) {
        accountListStateWrapper.unwrap(
            onAddAccount = { accountType ->
                val route = registerRouteResultReceiver(
                    WearLoginMethodRoute(accountType),
                ) {
                    controller.queue(NavigationIntent.Pop)
                }
                controller.queue(NavigationIntent.NavigateToRoute(route))
            },
        )
    }

    val securityRoute = remember(securitySettingsRouteFactory) {
        securitySettingsRouteFactory.create()
    }
    val autofillRoute = remember(autofillSettingsRouteFactory) {
        autofillSettingsRouteFactory.create()
    }
    val uiRoute = remember(uiSettingsRouteFactory) {
        uiSettingsRouteFactory.create()
    }
    val otherRoute = remember(otherSettingsRouteFactory) {
        otherSettingsRouteFactory.create()
    }

    val items = rememberWearSettingsItems(
        accountsRaw = accountListState.items,
        addNewAccountOptions = accountListState.addNewAccountOptions,
        autofillRoute = autofillRoute,
        securityRoute = securityRoute,
        uiRoute = uiRoute,
        otherRoute = otherRoute,
    )

    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    WearScaffoldScreen(
        title = stringResource(Res.string.settings_main_header_title),
    ) { transformationSpec ->
        items(items, key = { it.id }) {
            when (it) {
                is WearSettingsListItem -> {
                    WearSettingsListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = it,
                        onClick = {
                            val intent = NavigationIntent.NavigateToRoute(it.route)
                            navigationController.queue(intent)
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is WearSettingsListSection -> {
                    WearSettingsListSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = it,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is WearSettingsListAccountItem -> {
                    WearSettingsListAccountItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = it,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is WearSettingsListAccountAdd -> {
                    WearSettingsListAccountAdd(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        item = it,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberWearSettingsItems(
    accountsRaw: List<AccountItem>,
    addNewAccountOptions: List<ContextItem>,
    autofillRoute: Route,
    securityRoute: Route,
    uiRoute: Route,
    otherRoute: Route,
): List<WearSettingsListItemBase> {
    val accounts = remember(accountsRaw, addNewAccountOptions) {
        accountsRaw
            .mapNotNull { item ->
                if (item !is AccountItem.Item) {
                    return@mapNotNull null
                }
                WearSettingsListAccountItem(
                    id = "account." + item.id,
                    item = item,
                )
            } + WearSettingsListAccountAdd(
                id = "account.new",
                actions = addNewAccountOptions,
            )
    }
    val routeItems = rememberSettingsRouteListItems(
        autofillRoute = autofillRoute,
        securityRoute = securityRoute,
        uiRoute = uiRoute,
        otherRoute = otherRoute,
        includeAutofill = true,
        includeWatchtower = !isRelease,
        includeNotifications = false,
        includeDebug = !isRelease,
    )
    val items = remember(
        accounts,
        routeItems,
    ) {
        val routeSettingsItems = routeItems
            .map { item ->
                when (item) {
                    is SettingsRouteListSection -> WearSettingsListSection(
                        id = item.id,
                        title = item.title,
                    )

                    is SettingsRouteListAction -> WearSettingsListItem(
                        id = item.id,
                        title = item.title,
                        text = item.text,
                        icon = item.icon,
                        route = item.route,
                    )
                }
            }
        accounts + routeSettingsItems
    }
    return items
}
