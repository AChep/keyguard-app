package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.URL_PURCHASE
import com.artemchep.keyguard.common.model.Premium
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.util.hasWebPurchase
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_premium_membership_buy_license_title
import com.artemchep.keyguard.ui.icons.ChevronIcon
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.core.scope.Scope

fun settingLicensePurchaseProvider(
    koinScope: Scope,
): SettingComponent = if (CurrentPlatform.hasWebPurchase()) {
    settingLicensePurchaseProvider(
        getPurchased = koinScope.get(),
    )
} else {
    // Store builds sell premium through their store.
    flowOf(null)
}

fun settingLicensePurchaseProvider(
    getPurchased: GetPurchased,
): SettingComponent = getPurchased()
    .map { premium ->
        if (premium != Premium.PURCHASED) {
            SettingIi(
                search = SettingIi.Search(
                    group = "subscription",
                    tokens = listOf(
                        "license",
                        "purchase",
                        "buy",
                    ),
                ),
            ) {
                SettingLicensePurchase()
            }
        } else {
            null
        }
    }

@Composable
private fun SettingLicensePurchase() {
    val controller by rememberUpdatedState(LocalNavigationController.current)
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.ShoppingCart,
        title = stringResource(Res.string.pref_item_premium_membership_buy_license_title),
        trailing = {
            ChevronIcon()
        },
        onClick = {
            val intent = NavigationIntent.NavigateToBrowser(URL_PURCHASE)
            controller.queue(intent)
        },
    )
}
