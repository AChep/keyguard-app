package com.artemchep.keyguard.apple.billing

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.orNull
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
import com.artemchep.keyguard.common.service.subscription.SubscriptionService
import com.artemchep.keyguard.common.usecase.GetCachePremium
import com.artemchep.keyguard.common.usecase.GetDebugPremium
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetPurchased
import com.artemchep.keyguard.common.usecase.PutCachePremium
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.platform.util.isRelease
import kotlinx.coroutines.flow.Flow
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

/** Cache only StoreKit verdicts; linked licenses and debug grants have their own lifetimes. */
class GetPurchasedApple(
    private val config: FlavorConfig,
    subscriptionService: SubscriptionService,
    getDebugPremium: GetDebugPremium,
    getCachePremium: GetCachePremium,
    putCachePremium: PutCachePremium,
    getLicensePremium: GetLicensePremium,
    windowCoroutineScope: WindowCoroutineScope,
    release: Boolean = isRelease,
) : GetPurchased {
    private val store = merge(
        getCachePremium().map { PremiumStatus(it, false) },
        subscriptionService.purchased().mapNotNull { it.orNull() }
            .distinctUntilChanged()
            .onEach { putCachePremium(it).attempt().bind() }
            .map { PremiumStatus(it, true) },
    ).runningReduce { old, new -> if (old.upstream && !new.upstream) old else new }
        .map { it.premium }

    private val premium = combine(
        store,
        getLicensePremium().onStart { emit(false) },
        if (release) flowOf(false) else getDebugPremium().onStart { emit(false) },
    ) { purchased, licensed, debug -> purchased || licensed || debug }
        .distinctUntilChanged()
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override fun invoke(): Flow<Boolean> = if (config.isFreeAsBeer) flowOf(true) else premium

    private data class PremiumStatus(val premium: Boolean, val upstream: Boolean)
}
