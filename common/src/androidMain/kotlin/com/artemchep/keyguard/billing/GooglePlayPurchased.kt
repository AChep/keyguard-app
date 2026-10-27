package com.artemchep.keyguard.billing

import android.content.Context
import arrow.core.identity
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull

/**
 * Google Play verdicts: emits nothing while the billing is loading
 * or failed in an unknown way.
 */
internal fun SubscriptionService.googlePlayPurchased(
    hasGooglePlayServices: Boolean,
): Flow<Boolean> {
    if (!hasGooglePlayServices) {
        // If a device doesn't have Google Play Services, then we
        // just give a user premium status.
        return flowOf(true)
    }

    return purchased()
        .mapNotNull { result ->
            result.fold(
                ifFailure = { e ->
                    if (e is BillingResponseException) {
                        val isGappsIssue = e.isFatalError() ||
                                e.isNotSupported()
                        isGappsIssue.takeIf { it }
                    } else {
                        // Something went wrong, so we
                        // have no idea.
                        null
                    }
                },
                ifLoading = { null },
                ifSuccess = ::identity,
            )
        }
}

/**
 * Returns `true` if a device has Google play services,
 * `false` otherwise.
 */
internal fun Context.hasGooglePlayServices(): Boolean {
    val apiAvailability = GoogleApiAvailability.getInstance()
    val status = apiAvailability.isGooglePlayServicesAvailable(this)
    return status == ConnectionResult.SUCCESS ||
            apiAvailability.isUserResolvableError(status)
}
