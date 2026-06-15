package com.artemchep.keyguard.wear.feature.auth

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

internal fun shouldShowPhoneLoginProgress(
    requestState: CompanionAuthRequestState?,
): Boolean = when (requestState) {
    CompanionAuthRequestState.WaitingForPhone,
    CompanionAuthRequestState.Importing,
    -> true

    is CompanionAuthRequestState.Cancelled,
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
        is CompanionAuthRequestState.Cancelled,
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
