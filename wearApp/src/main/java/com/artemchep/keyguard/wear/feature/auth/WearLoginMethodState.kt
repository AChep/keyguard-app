package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_keepass_wear_note
import com.artemchep.keyguard.res.addaccount_method_phone_failed
import com.artemchep.keyguard.res.addaccount_method_phone_importing
import com.artemchep.keyguard.res.addaccount_method_phone_text
import com.artemchep.keyguard.res.addaccount_method_phone_unavailable
import com.artemchep.keyguard.res.addaccount_method_phone_waiting
import com.artemchep.keyguard.wear.feature.navigation.BitwardenLoginRouteFactoryWear

data class WearLoginMethodState(
    val infoText: TextHolder? = null,
    val phoneStatusText: TextHolder = Res.string.addaccount_method_phone_text.wrap(),
    val isPhoneLoginEnabled: Boolean = false,
    val onPhoneLoginClick: (() -> Unit)? = null,
    val isManualLoginVisible: Boolean = true,
    val onManualLoginClick: (() -> Unit)? = null,
)

internal fun companionAuthProviderOf(
    accountType: AccountType,
): CompanionAuthProvider = when (accountType) {
    AccountType.BITWARDEN -> CompanionAuthProvider.BITWARDEN
    AccountType.KEEPASS -> CompanionAuthProvider.KEEPASS
}

internal fun manualLoginRouteOf(
    accountType: AccountType,
): RouteForResult<Unit> = when (accountType) {
    AccountType.BITWARDEN -> BitwardenLoginRouteFactoryWear.create()
    AccountType.KEEPASS -> KeePassLoginRoute
}

internal fun infoTextOf(
    accountType: AccountType,
): TextHolder? = when (accountType) {
    AccountType.BITWARDEN -> null
    AccountType.KEEPASS -> Res.string.addaccount_method_keepass_wear_note.wrap()
}

internal fun shouldShowManualLogin(
    accountType: AccountType,
): Boolean = when (accountType) {
    AccountType.BITWARDEN -> true
    AccountType.KEEPASS -> false
}

internal fun shouldEnablePhoneLogin(
    isPhoneAvailable: Boolean,
): Boolean = isPhoneAvailable

internal fun phoneStatusTextOf(
    isPhoneAvailable: Boolean,
    requestState: CompanionAuthRequestState?,
): TextHolder = when (requestState) {
    CompanionAuthRequestState.WaitingForPhone -> Res.string.addaccount_method_phone_waiting.wrap()
    CompanionAuthRequestState.Importing -> Res.string.addaccount_method_phone_importing.wrap()
    is CompanionAuthRequestState.Cancelled -> requestState.message?.let(TextHolder::Value)
        ?: Res.string.addaccount_method_phone_failed.wrap()
    is CompanionAuthRequestState.Failed -> requestState.message?.let(TextHolder::Value)
        ?: Res.string.addaccount_method_phone_failed.wrap()

    CompanionAuthRequestState.Success,
    null,
    -> {
        if (isPhoneAvailable) {
            Res.string.addaccount_method_phone_text.wrap()
        } else {
            Res.string.addaccount_method_phone_unavailable.wrap()
        }
    }
}
