package com.artemchep.keyguard.feature.home.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.artemchep.keyguard.common.usecase.GetAccountsHasError
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.home.settings.accounts.AccountsRoute
import com.artemchep.keyguard.feature.home.settings.autofill.AutofillSettingsRoute
import com.artemchep.keyguard.feature.home.settings.debug.DebugSettingsRoute
import com.artemchep.keyguard.feature.home.settings.display.UiSettingsRoute
import com.artemchep.keyguard.feature.home.settings.notifications.NotificationsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.other.OtherSettingsRoute
import com.artemchep.keyguard.feature.home.settings.search.SearchSettingsRoute
import com.artemchep.keyguard.feature.home.settings.security.SecuritySettingsRoute
import com.artemchep.keyguard.feature.home.settings.subscriptions.SubscriptionsSettingsRoute
import com.artemchep.keyguard.feature.home.settings.watchtower.WatchtowerSettingsRoute
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.LocalNavigationRouter
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.onboarding.SmallOnboardingCard
import com.artemchep.keyguard.feature.onboarding.onboardingItemsPremium
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.isStandalone
import com.artemchep.keyguard.platform.util.hasAutofill
import com.artemchep.keyguard.platform.util.hasSubscription
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardPremium
import com.artemchep.keyguard.ui.pulltosearch.PullToSearch
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import dev.icerock.moko.resources.compose.stringResource
import org.kodein.di.compose.rememberInstance

sealed interface SettingsItem2 {
    val id: String
}

data class SettingsItem(
    override val id: String,
    val title: TextHolder,
    val text: TextHolder,
    val icon: ImageVector? = null,
    val leading: (@Composable RowScope.() -> Unit)? = null,
    val trailing: (@Composable RowScope.() -> Unit)? = null,
    val content: (@Composable RowScope.() -> Unit)? = null,
    val route: Route,
) : SettingsItem2

data class SettingsSectionItem(
    override val id: String,
    val title: TextHolder?,
) : SettingsItem2

private val items = listOfNotNull<SettingsItem2>(
    SettingsItem(
        id = "accounts",
        title = TextHolder.Res(Res.strings.pref_item_accounts_title),
        text = TextHolder.Res(Res.strings.pref_item_accounts_text),
        icon = Icons.Outlined.AccountBox,
        trailing = {
            val getAccountsHasError: GetAccountsHasError by rememberInstance()
            // Show a badge if any of the accounts have
            // a failed sync attempt.
            val visible by remember(getAccountsHasError) {
                val settingsAlertVisibleFlow = getAccountsHasError()
                settingsAlertVisibleFlow
            }.collectAsState(initial = false)
            ExpandedIfNotEmptyForRow(
                valueOrNull = Unit.takeIf { visible },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    tint = MaterialTheme.colorScheme.error,
                    contentDescription = null,
                )
            }
        },
        route = AccountsRoute,
    ),
    SettingsSectionItem(
        id = "seee",
        title = null, // TextHolder.Value("Options"),
    ),
    SettingsItem(
        id = "subscription",
        title = TextHolder.Res(Res.strings.pref_item_subscription_title),
        text = TextHolder.Res(Res.strings.pref_item_subscription_text),
        leading = {
            val getPurchased by rememberInstance<GetPurchased>()
            val isPurchased by remember(getPurchased) {
                getPurchased()
            }.collectAsState(false)

            val targetTint =
                if (isPurchased) MaterialTheme.colorScheme.primary else LocalContentColor.current
            val tint by animateColorAsState(targetValue = targetTint)
            Icon(
                Icons.Outlined.KeyguardPremium,
                null,
                tint = tint,
            )
        },
        content = {
            onboardingItemsPremium.forEach { item ->
                SmallOnboardingCard(
                    modifier = Modifier,
                    title = stringResource(item.title),
                    text = stringResource(item.text),
                    imageVector = item.icon,
                )
            }
        },
        route = SubscriptionsSettingsRoute,
    ).takeIf { CurrentPlatform.hasSubscription() && !isStandalone },
//    SettingsItem(
//        id = "onboarding",
//        title = "Onboarding",
//        text = "Learn more about the Keyguard",
//        icon = Icons.Outlined.Info,
//        route = OnboardingRoute,
//    ),
//    SettingsSectionItem(
//        id = "seee22",
//        title = null, // TextHolder.Value("Settings"),
//    ),
    SettingsItem(
        id = "autofill",
        title = TextHolder.Res(Res.strings.pref_item_autofill_title),
        text = TextHolder.Res(Res.strings.pref_item_autofill_text),
        icon = Icons.Outlined.AutoAwesome,
        route = AutofillSettingsRoute,
    ).takeIf { CurrentPlatform.hasAutofill() },
    SettingsItem(
        id = "security",
        title = TextHolder.Res(Res.strings.pref_item_security_title),
        text = TextHolder.Res(Res.strings.pref_item_security_text),
        icon = Icons.Outlined.Lock,
        route = SecuritySettingsRoute,
    ),
    SettingsItem(
        id = "watchtower",
        title = TextHolder.Res(Res.strings.pref_item_watchtower_title),
        text = TextHolder.Res(Res.strings.pref_item_watchtower_text),
        icon = Icons.Outlined.Security,
        route = WatchtowerSettingsRoute,
    ).takeIf { !isRelease },
    SettingsItem(
        id = "notifications",
        title = TextHolder.Res(Res.strings.pref_item_notifications_title),
        text = TextHolder.Res(Res.strings.pref_item_notifications_text),
        icon = Icons.Outlined.Notifications,
        route = NotificationsSettingsRoute,
    ).takeIf { !isRelease },
    SettingsItem(
        id = "display",
        title = TextHolder.Res(Res.strings.pref_item_appearance_title),
        text = TextHolder.Res(Res.strings.pref_item_appearance_text),
        icon = Icons.Outlined.ColorLens,
        route = UiSettingsRoute,
    ),
    SettingsItem(
        id = "debug",
        title = TextHolder.Res(Res.strings.pref_item_dev_title),
        text = TextHolder.Res(Res.strings.pref_item_dev_text),
        icon = Icons.Outlined.Code,
        route = DebugSettingsRoute,
    ).takeIf { !isRelease },
    SettingsItem(
        id = "about",
        title = TextHolder.Res(Res.strings.pref_item_other_title),
        text = TextHolder.Res(Res.strings.pref_item_other_text),
        icon = Icons.Outlined.Info,
        route = OtherSettingsRoute,
    ),
)

@Composable
fun SettingListScreen() {
    SettingListScreenContent(
        items = items,
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterialApi::class,
)
@Composable
private fun SettingListScreenContent(
    items: List<SettingsItem2>,
) {
    val q = LocalNavigationController.current
    val l = LocalNavigationRouter.current.value
    val s = l
        .indexOfLast { it.id == "settings" }
    val t = if (s != -1) {
        l.subList(fromIndex = s, toIndex = l.size)
    } else {
        emptyList()
    }

    val pullRefreshState = rememberPullRefreshState(
        refreshing = false,
        onRefresh = {
            val route = SearchSettingsRoute
            val intent = NavigationIntent.NavigateToRoute(route)
            q.queue(intent)
        },
    )
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .pullRefresh(pullRefreshState)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.strings.settings_main_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    IconButton(
                        onClick = {
                            val route = SearchSettingsRoute
                            val intent = NavigationIntent.NavigateToRoute(route)
                            q.queue(intent)
                        },
                    ) {
                        IconBox(Icons.Outlined.Search)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        pullRefreshState = pullRefreshState,
        overlay = {
            PullToSearch(
                modifier = Modifier
                    .padding(contentPadding.value),
                pullRefreshState = pullRefreshState,
            )
        },
    ) {
        items(items, key = { it.id }) {
            when (it) {
                is SettingsItem -> {
                    SettingListItem(
                        selected = it.route === t.getOrNull(1)?.route,
                        item = it,
                        onClick = {
                            val intent = NavigationIntent.Composite(
                                listOf(
                                    NavigationIntent.PopById("settings"),
                                    NavigationIntent.NavigateToRoute(it.route),
                                ),
                            )
                            q.queue(intent)
                        },
                    )
                }

                is SettingsSectionItem -> {
                    val text = it.title?.let { textResource(it) }
                    Section(
                        text = text,
                    )
                }
            }
        }
    }
}
