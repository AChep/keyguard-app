package com.artemchep.keyguard.apple.vault

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultRecentState
import com.artemchep.keyguard.feature.home.vault.screen.vaultRecentScreenStateProducer
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import com.artemchep.keyguard.feature.home.vault.apple.assembleSiblingAppleVaultState
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.totpMapFlow
import com.artemchep.keyguard.apple.model.vaultItemFingerprint
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.tabs.CallsTabs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class RecentsController(
    private val ctx: CoreContext,
) {
    /** The live producer state, `null` while the vault is locked / no subscribers. */
    private val activeRecentState = MutableStateFlow<VaultRecentState?>(null)

    private val recentsTotpTokens = MutableStateFlow<List<Pair<String, TotpToken>>>(emptyList())

    private val framePublisher = VaultListFramePublisher()

    // Reference-counted master gate: runs the producer while ANY channel is
    // subscribed (the sheet is open), tears it down when the last one cancels.
    // Main-confined (every bridge entry point runs on the main thread), so the
    // counter needs no synchronisation.
    private var masterSubscribers = 0
    private var master: KeyguardCancellable? = null

    private fun retainMaster() {
        if (masterSubscribers++ == 0) {
            master = startMaster()
        }
    }

    private fun releaseMaster() {
        if (masterSubscribers > 0 && --masterSubscribers == 0) {
            master?.cancel()
            master = null
            // Cancelling the master does NOT run its `onLocked`, so wipe the shared
            // state here — a later re-open starts from a clean slate instead of
            // briefly assembling a frame from the previous session's stale state.
            activeRecentState.value = null
            recentsTotpTokens.value = emptyList()
        }
    }

    private fun startMaster(): KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            activeRecentState.value = null
            recentsTotpTokens.value = emptyList()
        },
    ) { state ->
        val producerFlow = with(state.sessionKoin) {
            ctx.koin.newHeadlessStateFlowScope("recents", this@launchSessionObserver)
                .vaultRecentScreenStateProducer(
                    highlightBackgroundColor = Color.Transparent,
                    highlightContentColor = Color.Transparent,
                    getAccounts = get(),
                    getCanWrite = get(),
                    getCiphers = get(),
                    getProfiles = get(),
                    getFolders = get(),
                    getCollections = get(),
                    getOrganizations = get(),
                    getTotpCode = get(),
                    getConcealFields = get(),
                    getAppIcons = get(),
                    getWebsiteIcons = get(),
                    getPasswordStrength = get(),
                    getCipherOpenedHistory = get(),
                    clearVaultSession = get(),
                    toolbox = get(),
                    queueSyncAll = get(),
                    syncSupervisor = get(),
                    dateFormatter = get(),
                    clipboardService = get(),
                )
        }
        producerFlow.collectLatest { loadable ->
            val recentState = loadable.getOrNull()
            activeRecentState.value = recentState
            if (recentState == null) {
                recentsTotpTokens.value = emptyList()
                return@collectLatest
            }
            // Keep the block alive and feed the TOTP-token channel.
            recentState.recent.collect { items ->
                recentsTotpTokens.value = items.mapNotNull { item ->
                    (item as? VaultItem2.Item)?.let { i -> i.token?.let { i.id to it } }
                }
            }
        }
    }

    /** Cancels [channel] and releases the master gate exactly once. */
    private fun trackChannel(channel: KeyguardCancellable): KeyguardCancellable {
        var released = false
        return KeyguardCancellable {
            channel.cancel()
            if (!released) {
                released = true
                releaseMaster()
            }
        }
    }

    /**
     * Delivered on a BACKGROUND thread by design, the SAME contract as [VaultListSession.observeListDelta].
     * The producer's `recent` list already reflects the selected tab.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecentsListDelta(
        onChange: (VaultListDelta) -> Unit,
    ): KeyguardCancellable {
        retainMaster()
        val channel = ctx.launchObserver {
            var revisionCounter = 0L
            val frames: Flow<AppleVaultListState?> = activeRecentState.flatMapLatest { recentState ->
                if (recentState == null) {
                    flowOf<AppleVaultListState?>(null)
                } else {
                    recentState.recent
                        .map { items ->
                            assembleSiblingAppleVaultState(
                                revision = ++revisionCounter,
                                items = items,
                                mode = AppMode.Main,
                                fingerprintOf = ::vaultItemFingerprint,
                            )
                        }
                        .throttleLatest()
                }
            }
            framePublisher.run(frames, onChange)
        }
        return trackChannel(channel)
    }

    /** Split off the item stream so the segmented picker never rides the item projection. Empty while locked. */
    fun observeRecentsTabs(
        onChange: (RecentsTabsSnapshot) -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        retainMaster()
        val channel = ctx.launchObserver {
            activeRecentState.collectLatest { recentState ->
                if (recentState == null) {
                    ctx.publishOnMain { onChange(RecentsTabsSnapshot.empty) }
                    return@collectLatest
                }
                val tabs = recentState.tabs.map { tab ->
                    RecentsTabSnapshot(
                        key = tab.key,
                        title = textResource(tab.title, leContext),
                    )
                }
                recentState.selectedTab
                    .map { selected ->
                        RecentsTabsSnapshot(
                            loaded = true,
                            tabs = tabs,
                            selectedTabKey = selected.key,
                        )
                    }
                    .distinctUntilChanged()
                    .collectOnMain { onChange(it) }
            }
        }
        return trackChannel(channel)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeRecentsTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable {
        retainMaster()
        val channel = ctx.launchSessionObserver(
            onLocked = { onChange(emptyMap()) },
        ) { state ->
            val getTotpCode = state.sessionKoin.get<GetTotpCodeWithOffset>()
            recentsTotpTokens
                .flatMapLatest { tokens -> totpMapFlow(getTotpCode, tokens) }
                .collectOnMain { onChange(it) }
        }
        return trackChannel(channel)
    }

    /**
     * Selects the Recents tab ("recents" / "favorites") by [CallsTabs.key]; the
     * shared producer persists the choice and re-emits the matching items.
     */
    fun setRecentsTab(key: String) {
        val tab = CallsTabs.entries.firstOrNull { it.key == key } ?: return
        activeRecentState.value?.onSelectTab?.invoke(tab)
    }
}
