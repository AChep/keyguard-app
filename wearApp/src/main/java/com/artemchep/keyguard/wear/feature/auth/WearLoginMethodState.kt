package com.artemchep.keyguard.wear.feature.auth

import com.artemchep.keyguard.feature.auth.companion.CompanionAuthError
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthPhoneDiscovery
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthRequestState
import com.artemchep.keyguard.feature.auth.keepass.KeePassLoginRoute
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.RouteForResult
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_method_keepass_wear_note
import com.artemchep.keyguard.res.addaccount_method_phone_cancelled
import com.artemchep.keyguard.res.addaccount_method_phone_checking
import com.artemchep.keyguard.res.addaccount_method_phone_error_import
import com.artemchep.keyguard.res.addaccount_method_phone_error_launch
import com.artemchep.keyguard.res.addaccount_method_phone_error_multiple_phones
import com.artemchep.keyguard.res.addaccount_method_phone_error_on_phone
import com.artemchep.keyguard.res.addaccount_method_phone_error_timeout
import com.artemchep.keyguard.res.addaccount_method_phone_error_too_large
import com.artemchep.keyguard.res.addaccount_method_phone_error_unreachable
import com.artemchep.keyguard.res.addaccount_method_phone_error_verification
import com.artemchep.keyguard.res.addaccount_method_phone_error_version
import com.artemchep.keyguard.res.addaccount_method_phone_failed
import com.artemchep.keyguard.res.addaccount_method_phone_importing
import com.artemchep.keyguard.res.addaccount_method_phone_services_unavailable
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
    phoneDiscovery: CompanionAuthPhoneDiscovery,
    requestState: CompanionAuthRequestState?,
): TextHolder = when (requestState) {
    CompanionAuthRequestState.WaitingForPhone -> Res.string.addaccount_method_phone_waiting.wrap()
    CompanionAuthRequestState.Importing -> Res.string.addaccount_method_phone_importing.wrap()
    CompanionAuthRequestState.Cancelled -> Res.string.addaccount_method_phone_cancelled.wrap()
    is CompanionAuthRequestState.Failed -> phoneErrorTextOf(requestState.error)

    CompanionAuthRequestState.Success,
    null,
    -> {
        when (phoneDiscovery) {
            CompanionAuthPhoneDiscovery.Loading -> Res.string.addaccount_method_phone_checking.wrap()
            CompanionAuthPhoneDiscovery.Unavailable -> Res.string.addaccount_method_phone_services_unavailable.wrap()
            is CompanionAuthPhoneDiscovery.Ready -> if (phoneDiscovery.devices.isNotEmpty()) {
                Res.string.addaccount_method_phone_text.wrap()
            } else {
                Res.string.addaccount_method_phone_unavailable.wrap()
            }
        }
    }
}

internal fun phoneErrorTextOf(
    error: CompanionAuthError,
): TextHolder = when (error) {
    CompanionAuthError.PHONE_UNAVAILABLE,
    CompanionAuthError.TRUSTED_PHONE_UNAVAILABLE,
    -> Res.string.addaccount_method_phone_error_unreachable

    CompanionAuthError.MULTIPLE_REACHABLE_PHONES -> Res.string.addaccount_method_phone_error_multiple_phones
    CompanionAuthError.LAUNCH_FAILED -> Res.string.addaccount_method_phone_error_launch
    CompanionAuthError.TIMEOUT -> Res.string.addaccount_method_phone_error_timeout
    CompanionAuthError.PAYLOAD_TOO_LARGE -> Res.string.addaccount_method_phone_error_too_large
    CompanionAuthError.UNSUPPORTED_PROTOCOL -> Res.string.addaccount_method_phone_error_version
    CompanionAuthError.MALFORMED_REQUEST_ID,
    CompanionAuthError.SECURITY_VALIDATION_FAILED,
    CompanionAuthError.INTEGRITY_CHECK_FAILED,
    -> Res.string.addaccount_method_phone_error_verification

    CompanionAuthError.REQUEST_FAILED -> Res.string.addaccount_method_phone_error_on_phone
    CompanionAuthError.IMPORT_FAILED -> Res.string.addaccount_method_phone_error_import
    CompanionAuthError.CANCELLED -> Res.string.addaccount_method_phone_cancelled
    CompanionAuthError.INVALID_REQUEST,
    CompanionAuthError.UNKNOWN,
    -> Res.string.addaccount_method_phone_failed
}.wrap()
