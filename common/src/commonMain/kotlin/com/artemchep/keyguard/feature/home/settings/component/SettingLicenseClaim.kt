package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import arrow.core.identity
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.clipboard.ClipboardService
import com.artemchep.keyguard.common.service.licensekey.LicenseClaimSource
import com.artemchep.keyguard.common.service.licensekey.model.LicenseEntitlement
import com.artemchep.keyguard.common.service.licensekey.model.LicenseStatus
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetClaimedLicenseEntitlement
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.SyncLicense
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.platform.util.isRelease
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.tooltip.Tooltip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.compose.rememberInstance
import org.kodein.di.instance
import org.kodein.di.instanceOrNull

fun settingLicenseClaimProvider(
    directDI: DirectDI,
): SettingComponent {
    val licenseClaimSource: LicenseClaimSource? = directDI.instanceOrNull()
    if (licenseClaimSource == null) {
        return flowOf(null)
    }

    return settingLicenseClaimProvider(
        subscriptionService = directDI.instance(),
        claimedLicenseEntitlement = directDI.instance(),
        syncLicense = directDI.instance(),
        showMessage = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )
}

fun settingLicenseClaimProvider(
    subscriptionService: SubscriptionService,
    claimedLicenseEntitlement: GetClaimedLicenseEntitlement,
    syncLicense: SyncLicense,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = combine(
    subscriptionService
        .purchased()
        .mapNotNull { purchasedResult ->
            purchasedResult.fold(
                ifFailure = { !isRelease },
                ifLoading = { null },
                ifSuccess = ::identity,
            )
        },
    claimedLicenseEntitlement(),
) { purchasesUsingIap, entitlement ->
    if (!purchasesUsingIap && entitlement == null) {
        return@combine null
    }

    SettingIi(
        search = SettingIi.Search(
            group = "subscription",
            tokens = listOf(
                "license",
                "sync",
                "claim",
                "purchase",
                "premium",
                "subscription",
            ),
        ),
    ) {
        SettingLicenseClaim(
            entitlement = entitlement,
            syncLicense = syncLicense,
            showMessage = showMessage,
            windowCoroutineScope = windowCoroutineScope,
        )
    }
}

@Composable
private fun SettingLicenseClaim(
    entitlement: LicenseEntitlement?,
    syncLicense: SyncLicense,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
) {
    val updatedSyncLicense by rememberUpdatedState(syncLicense)
    val updatedShowMessage by rememberUpdatedState(showMessage)
    val updatedWindowCoroutineScope by rememberUpdatedState(windowCoroutineScope)

    val title = stringResource(Res.string.pref_item_license_sync_title)
    val text = stringResource(Res.string.pref_item_license_sync_text)

    val requestedMessage = stringResource(Res.string.pref_item_license_sync_requested)
    val syncedMessage = stringResource(Res.string.pref_item_license_sync_success)
    val alreadyMessage = stringResource(Res.string.pref_item_license_sync_already)
    val noPurchasesMessage = stringResource(Res.string.pref_item_license_sync_no_purchases)
    val failureMessage = stringResource(Res.string.pref_item_license_sync_failed)
    LocalSettingPaneComponents.current.KgAction(
        icon = null,
        title = title,
        text = text,
        onClick = {
            val preSyncMessage = ToastMessage(
                title = requestedMessage,
                type = ToastMessage.Type.INFO,
            )
            updatedShowMessage.copy(preSyncMessage)

            updatedSyncLicense()
                .effectTap(Dispatchers.Main) { result ->
                    val message = result.toToastMessage(
                        provideSyncedMessage = { syncedMessage },
                        provideAlreadyMessage = { alreadyMessage },
                        provideNoPurchasesMessage = { noPurchasesMessage },
                        provideFailureMessage = { failureMessage },
                    )
                    updatedShowMessage.copy(message)
                }
                .handleErrorTap {
                    val message = ToastMessage(
                        title = failureMessage,
                        type = ToastMessage.Type.ERROR,
                    )
                    updatedShowMessage.copy(message)
                }
                .attempt()
                .launchIn(updatedWindowCoroutineScope)
            Unit
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

@Composable
fun LicenseEntitlementFooter(
    entitlement: LicenseEntitlement,
) {
    val visibilityState = remember {
        VisibilityState(isVisible = false)
    }

    val clipboardService by rememberInstance<ClipboardService>()
    Column(
        modifier = Modifier
            .padding(
                start = Dimens.contentPadding,
                end = Dimens.contentPadding,
                top = 4.dp,
                bottom = 4.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                val keyText = remember(entitlement.licenseKey) {
                    AnnotatedString(entitlement.licenseKey)
                }
                val key = animatedConcealedText(
                    text = keyText,
                    concealed = !visibilityState.isVisible,
                )
                Text(
                    text = key,
                    fontFamily = monoFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                ExpandedIfNotEmpty(
                    valueOrNull = entitlement.status
                        .takeIf { visibilityState.isVisible },
                ) { state ->
                    LicenseStatusBadge(state)
                }
            }

            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )

            ExpandedIfNotEmptyForRow(
                valueOrNull = entitlement.status
                    .takeIf { !visibilityState.isVisible },
            ) { state ->
                LicenseStatusIcon(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(24.dp),
                    state = state,
                )
            }

            VisibilityToggle(
                visibilityState = visibilityState,
            )

            IconButton(
                onClick = {
                    clipboardService.setPrimaryClip(
                        value = entitlement.licenseKey,
                        concealed = true,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = null,
                )
            }
        }
    }
}

private inline fun SyncLicense.Result.toToastMessage(
    provideSyncedMessage: () -> String,
    provideAlreadyMessage: () -> String,
    provideNoPurchasesMessage: () -> String,
    provideFailureMessage: () -> String,
) = when (this) {
    is SyncLicense.Result.Synced -> ToastMessage(
        title = provideSyncedMessage(),
        type = ToastMessage.Type.SUCCESS,
    )

    is SyncLicense.Result.AlreadyLicensed -> ToastMessage(
        title = provideAlreadyMessage(),
        type = ToastMessage.Type.SUCCESS,
    )

    SyncLicense.Result.NoPurchases -> ToastMessage(
        title = provideNoPurchasesMessage(),
        type = ToastMessage.Type.ERROR,
    )

    is SyncLicense.Result.NotLicensed,
    SyncLicense.Result.Unsupported,
        -> ToastMessage(
        title = provideFailureMessage(),
        type = ToastMessage.Type.ERROR,
    )
}

@Composable
private fun LicenseStatusBadge(
    state: LicenseStatus,
) {
    val type = when (state) {
        LicenseStatus.ACTIVE -> TextFieldModel.Vl.Type.SUCCESS
        LicenseStatus.GRACE,
        LicenseStatus.EXPIRED,
        LicenseStatus.REVOKED,
        LicenseStatus.REFUNDED,
        LicenseStatus.PENDING
            -> TextFieldModel.Vl.Type.INFO
        LicenseStatus.INVALID,
        LicenseStatus.UNKNOWN,
            -> TextFieldModel.Vl.Type.ERROR
    }
    val text = rememberLicenseStatusText(state)

    FlatTextFieldBadge(
        type = type,
        text = text,
    )
}

@Composable
private fun LicenseStatusIcon(
    modifier: Modifier = Modifier,
    state: LicenseStatus,
) {
    val color = when (state) {
        LicenseStatus.ACTIVE -> MaterialTheme.colorScheme.ok
        LicenseStatus.GRACE,
        LicenseStatus.EXPIRED,
        LicenseStatus.REVOKED,
        LicenseStatus.REFUNDED,
        LicenseStatus.PENDING
            -> MaterialTheme.colorScheme.info
        LicenseStatus.INVALID,
        LicenseStatus.UNKNOWN,
            -> MaterialTheme.colorScheme.error
    }
    val icon = when (state) {
        LicenseStatus.ACTIVE -> Icons.Outlined.Check
        LicenseStatus.GRACE,
        LicenseStatus.EXPIRED,
        LicenseStatus.REVOKED,
        LicenseStatus.REFUNDED,
        LicenseStatus.PENDING
            -> Icons.Outlined.Info
        LicenseStatus.INVALID,
        LicenseStatus.UNKNOWN,
            -> Icons.Outlined.ErrorOutline
    }
    val text = rememberLicenseStatusText(state)

    Tooltip(
        modifier = modifier,
        valueOrNull = text,
        tooltip = { t ->
            Text(t)
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
        )
    }
}

@Composable
private fun rememberLicenseStatusText(status: LicenseStatus): String {
    val text = status.name
    return text
}
