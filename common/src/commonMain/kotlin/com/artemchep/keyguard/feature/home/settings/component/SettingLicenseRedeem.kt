package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.isCurrentlyLicensed
import com.artemchep.keyguard.common.usecase.GetLicenseEntitlement
import com.artemchep.keyguard.common.usecase.RedeemLicenseKey
import com.artemchep.keyguard.common.usecase.RemoveLicense
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.icons.icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

private const val LICENSE_KEY_ITEM_KEY = "license_key"

fun settingRedeemLicenseProvider(
    directDI: DirectDI,
) = settingRedeemLicenseProvider(
    getLicenseEntitlement = directDI.instance(),
    redeemLicenseKey = directDI.instance(),
    removeLicense = directDI.instance(),
    confirmationRouteFactory = directDI.instance(),
    showMessage = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingRedeemLicenseProvider(
    getLicenseEntitlement: GetLicenseEntitlement,
    redeemLicenseKey: RedeemLicenseKey,
    removeLicense: RemoveLicense,
    confirmationRouteFactory: ConfirmationRouteFactory,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getLicenseEntitlement()
    .map { entitlement ->
    val onRedeem = { licenseKey: String, successMessage: String, failureMessage: String ->
        redeemLicenseKey(licenseKey)
            .effectTap(Dispatchers.Main) { result ->
                val licensed = result.isCurrentlyLicensed()
                val message = ToastMessage(
                    title = if (licensed) successMessage else failureMessage,
                    type = if (licensed) ToastMessage.Type.SUCCESS else ToastMessage.Type.ERROR,
                )
                showMessage.copy(message)
            }
            .attempt()
            .launchIn(windowCoroutineScope)
        Unit
    }
    val onRemove = {
        removeLicense()
            .attempt()
            .launchIn(windowCoroutineScope)
        Unit
    }

    SettingIi(
        search = SettingIi.Search(
            group = "subscription",
            tokens = listOf(
                "license",
                "key",
                "redeem",
                "premium",
                "activate",
            ),
        ),
    ) {
        SettingRedeemLicense(
            entitlement = entitlement,
            confirmationRouteFactory = confirmationRouteFactory,
            onRedeem = onRedeem,
            onRemove = onRemove,
        )
    }
}

@Composable
private fun SettingRedeemLicense(
    entitlement: LicenseEntitlement?,
    confirmationRouteFactory: ConfirmationRouteFactory,
    onRedeem: (key: String, successMessage: String, failureMessage: String) -> Unit,
    onRemove: () -> Unit,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val updatedEntitlement by rememberUpdatedState(entitlement)
    val updatedConfirmationRouteFactory by rememberUpdatedState(confirmationRouteFactory)
    val updatedOnRedeem by rememberUpdatedState(onRedeem)
    val updatedOnRemove by rememberUpdatedState(onRemove)

    val title = stringResource(Res.string.pref_item_license_key_title)
    val fieldLabel = stringResource(Res.string.pref_item_license_key_field_label)

    val redeemedMessage = stringResource(Res.string.pref_item_license_key_message_redeemed)
    val invalidMessage = stringResource(Res.string.pref_item_license_key_message_invalid)
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = title,
        onClick = {
            val route = updatedConfirmationRouteFactory.registerRouteResultReceiver(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Key),
                    title = title,
                    items = listOf(
                        ConfirmationRoute.Args.Item.StringItem(
                            key = LICENSE_KEY_ITEM_KEY,
                            value = updatedEntitlement?.licenseKey.orEmpty(),
                            title = fieldLabel,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
                            canBeEmpty = true,
                        ),
                    ),
                ),
            ) { result ->
                if (result !is ConfirmationResult.Confirm) {
                    return@registerRouteResultReceiver
                }

                val licenseKey = (result.data[LICENSE_KEY_ITEM_KEY] as? String)
                    ?.trim()
                    ?: return@registerRouteResultReceiver
                if (licenseKey.isEmpty()) {
                    // Clearing the field removes
                    // the stored license.
                    updatedOnRemove()
                } else {
                    updatedOnRedeem(licenseKey, redeemedMessage, invalidMessage)
                }
            }
            val intent = NavigationIntent.NavigateToRoute(
                route = route,
            )
            navigationController.queue(intent)
        },
        footer = {
            ExpandedIfNotEmpty(
                entitlement
            ) {
                LicenseEntitlementFooter(
                    entitlement = it,
                )
            }
        },
    )
}
