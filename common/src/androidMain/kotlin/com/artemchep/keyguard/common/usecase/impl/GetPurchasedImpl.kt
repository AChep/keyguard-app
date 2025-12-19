package com.artemchep.keyguard.common.usecase.impl

import android.content.Context
import arrow.core.identity
import com.artemchep.keyguard.billing.BillingResponseException
import com.artemchep.keyguard.billing.isFatalError
import com.artemchep.keyguard.billing.isNotSupported
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.util.isRelease
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.shareIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetPurchasedImpl(
    private val context: Context,
    private val config: FlavorConfig,
    private val subscriptionService: SubscriptionService,
    private val getDebugPremium: GetDebugPremium,
    private val getCachePremium: GetCachePremium,
    private val putCachePremium: PutCachePremium,
    private val windowCoroutineScope: WindowCoroutineScope,
) : GetPurchased {
    constructor(directDI: DirectDI) : this(
        context = directDI.instance(),
        config = directDI.instance(),
        subscriptionService = directDI.instance(),
        getDebugPremium = directDI.instance(),
        getCachePremium = directDI.instance(),
        putCachePremium = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    private val sharedFlow = merge(
        upstreamStatusFlow(),
        localStatusFlow(),
    )
        .runningReduce { y, x ->
            x.takeIf { it.isUpstream }
                ?: y.takeIf { it.isUpstream }
                ?: x
        }
        .onEach { status ->
            // Cache the latest upstream value, so next time we
            // open the app and billing refuses to work, we still
            // have valid license.
            if (status.isUpstream) {
                putCachePremium(status.isPremium)
                    .attempt() // do not notify a user about the error
                    .launchIn(windowCoroutineScope)
            }
        }
        .map { it.isPremium }
        .distinctUntilChanged()
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(), replay = 1)

    override fun invoke() = if (!config.isFreeAsBeer) sharedFlow else flowOf(true)

    private fun localStatusFlow() = getCachePremium()
        .map { isPremium ->
            PremiumStatus(
                isPremium = isPremium,
                isUpstream = false,
            )
        }

    private fun upstreamStatusFlow() = kotlin.run {
        val hasGooglePlayServices = hasGooglePlayServices()
        if (!hasGooglePlayServices) {
            // If a device doesn't have Google Play Services, then we
            // just give a user premium status.
            return@run flowOf(true)
        }

        val isPurchased = subscriptionService
            .purchased()
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
        if (!isRelease) {
            combine(
                isPurchased
                    // We want to emit something here, so
                    // we can use the test flow.
                    .onStart {
                        emit(false)
                    },
                getDebugPremium(),
            ) { a, b -> a || b }
        } else {
            isPurchased
        }
    }.map { isPremium ->
        PremiumStatus(
            isPremium = isPremium,
            isUpstream = true,
        )
    }

    private data class PremiumStatus(
        val isUpstream: Boolean,
        val isPremium: Boolean,
    )

    /**
     * Returns `true` if a device has Google play services,
     * `false` otherwise.
     */
    private fun hasGooglePlayServices(): Boolean {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val status = apiAvailability.isGooglePlayServicesAvailable(context)
        return status == ConnectionResult.SUCCESS ||
                apiAvailability.isUserResolvableError(status)
    }
}
