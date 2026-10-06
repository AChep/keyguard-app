package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import com.artemchep.keyguard.feature.home.vault.quicksearch.QuickSearchHeadlessController
import com.artemchep.keyguard.feature.home.vault.quicksearch.createQuickSearchHeadlessController
import com.artemchep.keyguard.feature.home.vault.apple.AppleVaultListState
import com.artemchep.keyguard.feature.home.vault.apple.assembleSiblingAppleVaultState
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.model.TotpFieldSnapshot
import com.artemchep.keyguard.apple.model.totpMapFlow
import com.artemchep.keyguard.apple.model.vaultItemFingerprint
import com.artemchep.keyguard.apple.throttleLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class QuickSearchController(
    private val ctx: CoreContext,
) {
    /**
     * A [MutableStateFlow] rather than a plain field, so [observeQuickSearchListDelta] can `flatMapLatest`
     * over a fresh session / a lock. `null` while the vault is locked / no observer.
     */
    private val quickSearchController = MutableStateFlow<QuickSearchHeadlessController?>(null)
    private var quickSearchOpenUrlHandler: ((String) -> Unit)? = null
    private val quickSearchTotpTokens = MutableStateFlow<List<Pair<String, TotpToken>>>(emptyList())

    // Only the newest observation may publish or clear the session state above.
    private var quickSearchGeneration = 0L

    private val framePublisher = VaultListFramePublisher()

    fun observeQuickSearch(
        onChange: (QuickSearchSnapshot) -> Unit,
    ): KeyguardCancellable {
        val observationGeneration = ++quickSearchGeneration
        return ctx.launchSessionObserver(
            // The controller holds decrypted results and the TOTP tokens hold
            // secrets; drop both as soon as the vault locks or the panel stops
            // observing (Swift stops it 300 s after the panel hides).
            onLocked = {
                if (quickSearchGeneration == observationGeneration) {
                    quickSearchController.value = null
                    quickSearchTotpTokens.value = emptyList()
                    onChange(QuickSearchSnapshot.empty)
                }
            },
            onTeardown = {
                if (quickSearchGeneration == observationGeneration) {
                    quickSearchController.value = null
                    quickSearchTotpTokens.value = emptyList()
                }
            },
        ) { state ->
            val controller = ctx.koin.newHeadlessStateFlowScope("quicksearch", this)
                .createQuickSearchHeadlessController(
                    collectScope = this,
                    sessionKoin = state.sessionKoin,
                    // Invoked synchronously from the Swift-driven
                    // perform* calls, i.e. on the main thread.
                    onOpenUrl = { url -> quickSearchOpenUrlHandler?.invoke(url) },
                )
            ctx.publishOnMain {
                if (quickSearchGeneration == observationGeneration) quickSearchController.value = controller
            }
            controller.state
                .throttleLatest()
                .collectOnMain { headless ->
                    if (quickSearchGeneration != observationGeneration) return@collectOnMain
                    quickSearchTotpTokens.value = headless.results.mapNotNull { r ->
                        r.token?.let { r.id to it }
                    }
                    onChange(headless.toSnapshot())
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeQuickSearchTotp(
        onChange: (Map<String, TotpFieldSnapshot>) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchSessionObserver(
            onLocked = { onChange(emptyMap()) },
        ) { state ->
            val getTotpCode = state.sessionKoin.get<GetTotpCodeWithOffset>()
            quickSearchTotpTokens
                .flatMapLatest { tokens -> totpMapFlow(getTotpCode, tokens) }
                .collectOnMain { onChange(it) }
        }
    }

    /**
     * Delivered on a BACKGROUND thread by design, the SAME contract as [VaultListSession.observeListDelta].
     * The keyboard-selected id, detail and actions stay on the on-main [observeQuickSearch] channel.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeQuickSearchListDelta(
        onChange: (VaultListDelta) -> Unit,
    ): KeyguardCancellable {
        return ctx.launchObserver {
            var revisionCounter = 0L
            val frames: Flow<AppleVaultListState?> = quickSearchController.flatMapLatest { controller ->
                if (controller == null) {
                    flowOf<AppleVaultListState?>(null)
                } else {
                    controller.results
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
    }

    /** Writes [text] into the quick-search field as a user edit. */
    fun setQuickSearchQuery(text: String) {
        quickSearchController.value?.setQuery(text)
    }

    fun clearQuickSearchQuery() {
        quickSearchController.value?.clearQuery()
    }

    fun moveQuickSearchSelection(direction: Int) {
        quickSearchController.value?.moveSelection(direction)
    }

    /** Moves the highlighted action of the selected result (Tab / Shift-Tab). */
    fun moveQuickSearchActionSelection(direction: Int) {
        quickSearchController.value?.moveActionSelection(direction)
    }

    fun selectQuickSearchItem(id: String) {
        quickSearchController.value?.selectItem(id)
    }

    fun invokeQuickSearchDefaultAction() {
        quickSearchController.value?.performDefaultAction()
    }

    /** Performs a specific action by its `QuickSearchHeadlessAction.type` name. */
    fun invokeQuickSearchAction(typeName: String) {
        quickSearchController.value?.performAction(typeName)
    }

    fun setQuickSearchOpenUrlHandler(handler: ((String) -> Unit)?) {
        quickSearchOpenUrlHandler = handler
    }
}
