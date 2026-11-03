package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDiscovery
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_phone_text
import com.artemchep.keyguard.res.addaccount_method_phone_pick
import com.artemchep.keyguard.res.retry

data class WearPhoneLoginState(
    val statusText: TextHolder = Res.string.addaccount_method_phone_text.wrap(),
    val showProgress: Boolean = false,
    val actionText: TextHolder? = null,
    val onActionClick: (() -> Unit)? = null,
)

internal enum class WearPhoneLoginLaunchBehavior {
    NONE,
    AUTO_START,
    PICK_DEVICE,
}

internal fun phoneLoginLaunchBehaviorOf(
    phoneDeviceCount: Int,
): WearPhoneLoginLaunchBehavior = when {
    phoneDeviceCount <= 0 -> WearPhoneLoginLaunchBehavior.NONE
    phoneDeviceCount == 1 -> WearPhoneLoginLaunchBehavior.AUTO_START
    else -> WearPhoneLoginLaunchBehavior.PICK_DEVICE
}

/**
 * The screen starts a login on its own only once, and never over a request
 * that is still running.
 */
internal fun shouldAutoLaunchPhoneLogin(
    phoneDeviceCount: Int,
    didAutoLaunch: Boolean,
    hasActiveRequest: Boolean,
): Boolean = phoneDeviceCount > 0 && !didAutoLaunch && !hasActiveRequest

internal fun shouldShowPhoneLoginProgress(
    requestState: CompanionAuthRequestState?,
): Boolean = when (requestState) {
    CompanionAuthRequestState.WaitingForPhone,
    CompanionAuthRequestState.Importing,
    -> true

    CompanionAuthRequestState.Cancelled,
    is CompanionAuthRequestState.Failed,
    CompanionAuthRequestState.Success,
    null,
    -> false
}

internal fun shouldEnablePhoneLoginAction(
    phoneDeviceCount: Int,
    requestState: CompanionAuthRequestState?,
): Boolean {
    if (phoneDeviceCount <= 0) {
        return false
    }

    return when (requestState) {
        null -> phoneDeviceCount > 1
        CompanionAuthRequestState.Cancelled,
        is CompanionAuthRequestState.Failed,
        -> true

        CompanionAuthRequestState.WaitingForPhone,
        CompanionAuthRequestState.Importing,
        CompanionAuthRequestState.Success,
        -> false
    }
}

internal fun phoneLoginActionTextOf(
    phoneDeviceCount: Int,
): TextHolder = if (phoneDeviceCount > 1) {
    Res.string.addaccount_method_phone_pick.wrap()
} else {
    TextHolder.Res(Res.string.retry)
}

internal fun wearPhoneLoginStateOf(
    phoneDiscovery: CompanionAuthPhoneDiscovery,
    requestState: CompanionAuthRequestState?,
    onActionClick: () -> Unit,
): WearPhoneLoginState {
    val phoneDeviceCount = phoneDiscovery.devices.size
    val actionEnabled = shouldEnablePhoneLoginAction(
        phoneDeviceCount = phoneDeviceCount,
        requestState = requestState,
    )
    return WearPhoneLoginState(
        statusText = phoneStatusTextOf(
            phoneDiscovery = phoneDiscovery,
            requestState = requestState,
        ),
        showProgress = shouldShowPhoneLoginProgress(requestState),
        actionText = if (actionEnabled) {
            phoneLoginActionTextOf(phoneDeviceCount)
        } else {
            null
        },
        onActionClick = if (actionEnabled) {
            onActionClick
        } else {
            null
        },
    )
}
