package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.service.settings.SettingsReadRepository
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCacheHiddenSend
import com.artemchep.keyguard.common.usecase.GetNavHiddenSend
import com.artemchep.keyguard.common.usecase.GetProfiles
import com.artemchep.keyguard.common.usecase.PutCacheHiddenSend
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.common.usecase.filterHiddenProfiles
import com.artemchep.keyguard.feature.home.settings.accounts.model.AccountType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetNavHiddenSendImpl(
    private val getAccounts: GetAccounts,
    private val getProfiles: GetProfiles,
    private val getCacheHiddenSend: GetCacheHiddenSend,
    private val putCacheHiddenSend: PutCacheHiddenSend,
    private val settingsReadRepository: SettingsReadRepository,
    private val windowCoroutineScope: WindowCoroutineScope,
) : GetNavHiddenSend {
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
                putCacheHiddenSend(status.hiddenSend)
                    .attempt() // do not notify a user about the error
                    .launchIn(windowCoroutineScope)
            }
        }
        .map { it.hiddenSend }
        .distinctUntilChanged()
        .stateIn(
            scope = GlobalScope,
            started = SharingStarted.Eagerly,
            initialValue = false, // most of the users are expected to have the Send tab shown
        )

    constructor(directDI: DirectDI) : this(
        getAccounts = directDI.instance(),
        getProfiles = directDI.instance(),
        getCacheHiddenSend = directDI.instance(),
        putCacheHiddenSend = directDI.instance(),
        settingsReadRepository = directDI.instance(),
        windowCoroutineScope = directDI.instance(),
    )

    override fun invoke() = sharedFlow

    private fun localStatusFlow() = getCacheHiddenSend()
        .map { hiddenSend ->
            HiddenSendStatus(
                hiddenSend = hiddenSend,
                isUpstream = false,
            )
        }

    private fun upstreamStatusFlow() = kotlin.run {
        settingsReadRepository.getNavHiddenSend()
            .flatMapLatest { hiddenSend ->
                // We have already decided to hide the Send tab,
                // no need to check the accounts.
                if (hiddenSend) {
                    return@flatMapLatest flowOf(true)
                }

                getHasAccountsWithSendSupportFlow()
                    .map { !it }
            }
    }.map { hiddenSend ->
        HiddenSendStatus(
            hiddenSend = hiddenSend,
            isUpstream = true,
        )
    }

    private data class HiddenSendStatus(
        val hiddenSend: Boolean,
        val isUpstream: Boolean,
    )

    /**
     * Returns a flow of Booleans whether we have any account
     * that can theoretically have sends or not.
     */
    private fun getHasAccountsWithSendSupportFlow() = combine(
        getAccounts(),
        getProfiles(),
    ) { accounts, profiles ->
        val shownAccountIds = profiles.asSequence()
            .filter { !it.hidden }
            .map { it.accountId }
            .toSet()
        accounts.filter { it.accountId() in shownAccountIds }
    }
        .mapNotNull { accounts ->
            // If the accounts read as empty, then do not change the
            // current value.
            if (accounts.isEmpty()) {
                return@mapNotNull null
            }

            accounts
                .any { account ->
                    account.type == AccountType.BITWARDEN
                }
        }
        .debounce(2000L) // for slow loading accounts
        .distinctUntilChanged()
}
