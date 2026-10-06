package com.artemchep.keyguard.apple.vault

import androidx.compose.ui.graphics.Color
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.totpMapFlow
import com.artemchep.keyguard.apple.model.vaultItemFingerprint
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.VaultState
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import com.artemchep.keyguard.feature.home.vault.apple.assembleSiblingAppleVaultState
import com.artemchep.keyguard.feature.home.vault.model.VaultItem2
import com.artemchep.keyguard.feature.home.vault.screen.VaultRecentState
import com.artemchep.keyguard.feature.home.vault.screen.vaultRecentScreenStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.tabs.CallsTabs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Creates independent Recents producers while reusing the vault list's projection and delta contract. */
internal class RecentsController(private val ctx: CoreContext) {
    fun makeSession(): RecentsSession = RecentsSession { onListDelta, publishTabs, publishTotp ->
        ctx.launchObserver {
            val active = MutableStateFlow<ActiveRecents?>(null)
            val master = ctx.launchSessionObserver(
                onLocked = { active.value = null },
                onTeardown = { active.value = null },
            ) { vault ->
                createProducer(vault).collectLatest { loadable ->
                    active.value = loadable.getOrNull()?.let { recent ->
                        ActiveRecents(recent, vault.sessionKoin.get())
                    }
                }
            }
            try {
                launch { VaultListFramePublisher().run(listFrames(active), onListDelta) }
                launch {
                    val leContext = ctx.koin.get<LeContext>()
                    active.collectLatest { source ->
                        val recent = source?.state
                        if (recent == null) {
                            ctx.publishOnMain { publishTabs(RecentsTabsSnapshot.empty, null) }
                        } else {
                            val tabs = recent.tabs.map { tab ->
                                RecentsTabSnapshot(tab.key, textResource(tab.title, leContext))
                            }
                            recent.selectedTab.collectOnMain { selected ->
                                publishTabs(RecentsTabsSnapshot(true, tabs, selected.key)) { key ->
                                    CallsTabs.entries.firstOrNull { it.key == key }
                                        ?.let { recent.onSelectTab?.invoke(it) }
                                }
                            }
                        }
                    }
                }
                launch { totpStates(active).collectOnMain(publishTotp) }
                awaitCancellation()
            } finally {
                master.cancel()
            }
        }
    }

    private suspend fun CoroutineScope.createProducer(vault: VaultState.Main) = with(vault.sessionKoin) {
        // A fresh scope owns every live field. Keep the stable disk key so a newly opened
        // sheet restores the last saved tab; DiskHandle only reads that preference at creation.
        ctx.koin.newHeadlessStateFlowScope("recents", this@createProducer)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun listFrames(active: Flow<ActiveRecents?>): Flow<AppleVaultListState?> {
        var revision = 0L
        return active.flatMapLatest { source ->
            source?.state?.recent?.map { items ->
                assembleSiblingAppleVaultState(
                    revision = ++revision,
                    items = items,
                    mode = AppMode.Main,
                    fingerprintOf = ::vaultItemFingerprint,
                )
            }?.throttleLatest() ?: flowOf(null)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun totpStates(active: Flow<ActiveRecents?>): Flow<Map<String, TotpFieldSnapshot>> =
        active.flatMapLatest { source ->
            if (source == null) return@flatMapLatest flowOf(emptyMap())
            source.state.recent
                .map { items ->
                    items.mapNotNull { item ->
                        (item as? VaultItem2.Item)?.let { row -> row.token?.let { row.id to it } }
                    }
                }
                .distinctUntilChanged()
                .flatMapLatest { tokens -> totpMapFlow(source.getTotpCode, tokens) }
        }

    private class ActiveRecents(val state: VaultRecentState, val getTotpCode: GetTotpCodeWithOffset)
}
