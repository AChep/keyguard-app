package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.service.flavor.FlavorConfig
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
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform

/**
 * Only the store verdicts are cached; linked licenses and debug grants have
 * their own lifetimes and must not wait for the store to respond.
 *
 * Licenses and debug grants are read from local storage and always emit, so
 * they get no `false` placeholder: one-shot reads would take it for a revoked
 * license.
 */
class GetPurchasedImpl(
    private val config: FlavorConfig,
    /**
     * Verified store verdicts. Emits nothing while the store is loading
     * or failed in an unknown way.
     */
    storePurchased: Flow<Boolean>,
    getLicensePremium: GetLicensePremium,
    getDebugPremium: GetDebugPremium,
    getCachePremium: GetCachePremium,
    putCachePremium: PutCachePremium,
    windowCoroutineScope: WindowCoroutineScope,
    release: Boolean = isRelease,
) : GetPurchased {
    private val storeFlow = merge(
        getCachePremium()
            .map { PremiumStatus(isPremium = it, isUpstream = false) },
        storePurchased
            .distinctUntilChanged()
            .transform { isPremium ->
                emit(PremiumStatus(isPremium = isPremium, isUpstream = true))
                // Cache the latest store verdict, so next time we
                // open the app and the store refuses to work, we
                // still have a valid purchase.
                putCachePremium(isPremium)
                    .attempt() // do not notify a user about the error
                    .bind()
            },
    )
        // A store verdict wins over cache values that come after it.
        .runningReduce { old, new -> if (old.isUpstream && !new.isUpstream) old else new }
        .map { it.isPremium }

    private val sharedFlow = combine(
        storeFlow,
        getLicensePremium(),
        if (release) flowOf(false) else getDebugPremium(),
    ) { purchased, licensed, debug -> purchased || licensed || debug }
        .distinctUntilChanged()
        .shareIn(windowCoroutineScope, SharingStarted.WhileSubscribed(5_000L), replay = 1)

    override fun invoke() = if (config.isFreeAsBeer) flowOf(true) else sharedFlow

    private data class PremiumStatus(
        val isUpstream: Boolean,
        val isPremium: Boolean,
    )
}
