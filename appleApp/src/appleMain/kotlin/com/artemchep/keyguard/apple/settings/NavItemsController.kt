package com.artemchep.keyguard.apple.settings

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.NavItemRef
import com.artemchep.keyguard.common.model.NavItemsConfigDefaults
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.iconImageVector
import com.artemchep.keyguard.common.service.filter.GetCipherFilters
import com.artemchep.keyguard.common.usecase.GetNavItemsConfig
import com.artemchep.keyguard.feature.home.navigation.HomeNavigationItem
import com.artemchep.keyguard.feature.home.navigation.resolveHomeNavigationItems
import com.artemchep.keyguard.feature.home.settings.navigation.NavigationItemsSettingsState
import com.artemchep.keyguard.feature.home.settings.navigation.navigationItemsSettingsStateProducer
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.dialog.DialogController
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * One top-level navigation destination, resolved from the shared
 * [com.artemchep.keyguard.common.model.NavItemsConfig] (order, visibility and
 * custom cipher-filter tabs already applied). Built-ins carry an empty [title]
 * and [iconHint] — Swift owns their L10n labels and SF Symbols, exactly like
 * the enum it replaces; cipher filters carry the filter name plus a stable
 * icon hint.
 */
data class NavSectionSnapshot(
    /** `vault`, `sends`, … or `cipher_filter:<id>`; stable row identity. */
    val key: String,
    val isCipherFilter: Boolean,
    /**
     * The navigation-stack scope this section renders (`setNavScope` +
     * `NavStackContainer`). Built-ins keep the pre-existing Swift scope names
     * (`send`, not `sends`); filters use their `cipher_filter:<id>` stack id.
     */
    val scope: String,
    val title: String,
    val iconHint: String,
    val filterId: String,
)

data class NavItemsSnapshot(
    val loaded: Boolean = false,
    val sections: List<NavSectionSnapshot> = emptyList(),
) {
    companion object {
        val empty = NavItemsSnapshot()
    }
}

/** One row of the "Navigation items" settings screen (configured or available-to-add). */
data class NavItemsSettingsRowSnapshot(
    /** The producer's stable ref key: `built_in:vault` / `cipher_filter:<id>`. */
    val key: String,
    val title: String,
    /** `""` if none (the configured rows; available rows carry the kind text). */
    val subtitle: String,
    val iconHint: String,
    val isCipherFilter: Boolean,
    val visible: Boolean,
    /** `false` for the pinned Vault / Settings rows and runtime-hidden ones. */
    val canToggleVisibility: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val canRemove: Boolean,
)

data class NavItemsSettingsSnapshot(
    val loaded: Boolean = false,
    val items: List<NavItemsSettingsRowSnapshot> = emptyList(),
    val availableItems: List<NavItemsSettingsRowSnapshot> = emptyList(),
) {
    companion object {
        val empty = NavItemsSettingsSnapshot()
    }
}

/**
 * Navigation-item customization: the resolved top-level section list the
 * SwiftUI shell renders ([observeNavItems]) and the "Navigation items"
 * settings screen ([observeNavItemsSettings]), the latter running the shared
 * [navigationItemsSettingsStateProducer] headlessly so hide / reorder / add /
 * remove / reset behave exactly like the Compose screen. The reset
 * confirmation is a `ConfirmationRoute` fired by the producer itself, routed
 * to the native dialog bridge via [DialogController.navigationInterceptor].
 *
 * Commands are looked up in main-confined `latest*` maps keyed by the
 * producer's stable ref keys (the `AppearanceController.latest*Variants`
 * pattern).
 */
internal class NavItemsController(
    private val ctx: CoreContext,
    private val dialogController: DialogController,
) {
    private var latestSettingsState: NavigationItemsSettingsState? = null
    private var latestItemsByKey: Map<String, NavigationItemsSettingsState.Item> = emptyMap()
    private var latestAvailableByKey: Map<String, NavigationItemsSettingsState.AvailableItem> = emptyMap()

    /**
     * Emits the resolved section list on every config / filter change; the
     * empty snapshot while locked (only the unlocked shell renders sections,
     * Swift falls back to the default six).
     */
    fun observeNavItems(
        onChange: (NavItemsSnapshot) -> Unit,
    ): KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            onChange(NavItemsSnapshot.empty)
        },
    ) { state ->
        val getNavItemsConfig = state.sessionKoin.get<GetNavItemsConfig>()
        val getCipherFilters = state.sessionKoin.get<GetCipherFilters>()
        combine(
            getNavItemsConfig(),
            getCipherFilters(),
        ) { config, filters ->
            val sections = resolveHomeNavigationItems(config, filters)
                .map { item -> item.toSectionSnapshot() }
            NavItemsSnapshot(loaded = true, sections = sections)
        }.collectOnMain(onChange)
    }

    fun observeNavItemsSettings(
        onChange: (NavItemsSettingsSnapshot) -> Unit,
    ): KeyguardCancellable = ctx.launchSessionObserver(
        onLocked = {
            latestSettingsState = null
            latestItemsByKey = emptyMap()
            latestAvailableByKey = emptyMap()
            onChange(NavItemsSettingsSnapshot.empty)
        },
    ) { state ->
        val sessionKoin = state.sessionKoin
        val leContext = ctx.koin.get<LeContext>()
        val producer = ctx.koin
            .newHeadlessStateFlowScope(
                key = "settings_navigation_items",
                scope = this,
                navigationInterceptor = dialogController.navigationInterceptor(sessionKoin = sessionKoin),
            )
            .navigationItemsSettingsStateProducer(
                // Effective config + filters are session-scoped; the persisted
                // config, the writer and the confirmation factory are global
                // bindings the sub-DI inherits.
                getNavItemsConfig = sessionKoin.get(),
                getPersistedNavItemsConfig = sessionKoin.get(),
                putNavItemsConfig = sessionKoin.get(),
                getCipherFilters = sessionKoin.get(),
                confirmationRouteFactory = sessionKoin.get(),
            )
        producer
            .map { loadable ->
                // The suspend TextHolder resolution stays off-main; only the
                // latest* handoff below runs on the main thread.
                val settingsState = loadable.getOrNull()
                val snapshot = projectSettings(settingsState, leContext)
                settingsState to snapshot
            }
            .collectOnMain { (settingsState, snapshot) ->
                latestSettingsState = settingsState
                latestItemsByKey = settingsState?.items
                    ?.associateBy { it.key }
                    .orEmpty()
                latestAvailableByKey = settingsState?.availableItems
                    ?.associateBy { it.key }
                    .orEmpty()
                onChange(snapshot)
            }
    }

    fun toggleNavItemVisibility(key: String) {
        latestItemsByKey[key]?.onVisibilityToggle?.invoke()
    }

    fun moveNavItemUp(key: String) {
        latestItemsByKey[key]?.onMoveUp?.invoke()
    }

    fun moveNavItemDown(key: String) {
        latestItemsByKey[key]?.onMoveDown?.invoke()
    }

    fun removeNavItem(key: String) {
        latestItemsByKey[key]?.onRemove?.invoke()
    }

    fun addNavItem(key: String) {
        latestAvailableByKey[key]?.onAdd?.invoke()
    }

    /**
     * Commits a drag-reorder: [keys] is the full row order the user dropped.
     * Keys that no longer resolve are skipped; the producer appends any
     * configured items missing from the list, so a stale reorder degrades
     * gracefully instead of dropping items.
     */
    fun reorderNavItems(keys: List<String>) {
        val state = latestSettingsState ?: return
        val refsByKey = state.items.associateBy({ it.key }, { it.ref })
        val refs = keys.mapNotNull { key -> refsByKey[key] }
        if (refs.isNotEmpty()) {
            state.onReorder(refs)
        }
    }

    /** Fires the producer's reset flow (native confirmation dialog, then defaults). */
    fun resetNavItems() {
        latestSettingsState?.onReset?.invoke()
    }

    private suspend fun projectSettings(
        state: NavigationItemsSettingsState?,
        leContext: LeContext,
    ): NavItemsSettingsSnapshot {
        if (state == null) return NavItemsSettingsSnapshot.empty
        val items = state.items.map { item ->
            NavItemsSettingsRowSnapshot(
                key = item.key,
                title = textResource(item.title, leContext),
                subtitle = "",
                iconHint = iconHint(item.ref, item.icon),
                isCipherFilter = item.ref is NavItemRef.CipherFilter,
                visible = item.visible,
                canToggleVisibility = item.onVisibilityToggle != null,
                canMoveUp = item.onMoveUp != null,
                canMoveDown = item.onMoveDown != null,
                canRemove = item.onRemove != null,
            )
        }
        val availableItems = state.availableItems.map { item ->
            NavItemsSettingsRowSnapshot(
                key = item.key,
                title = textResource(item.title, leContext),
                subtitle = item.text?.let { textResource(it, leContext) }.orEmpty(),
                iconHint = iconHint(item.ref, item.icon),
                isCipherFilter = item.ref is NavItemRef.CipherFilter,
                visible = true,
                canToggleVisibility = false,
                canMoveUp = false,
                canMoveDown = false,
                canRemove = false,
            )
        }
        return NavItemsSettingsSnapshot(
            loaded = true,
            items = items,
            availableItems = availableItems,
        )
    }
}

/** Kotlin built-in keys → the pre-existing Swift navigation-stack scopes. */
private val builtInScopes = mapOf(
    NavItemsConfigDefaults.BUILT_IN_VAULT to "vault",
    NavItemsConfigDefaults.BUILT_IN_SENDS to "send",
    NavItemsConfigDefaults.BUILT_IN_GENERATOR to "generator",
    NavItemsConfigDefaults.BUILT_IN_GPG_TOOLS to "gpg_tools",
    NavItemsConfigDefaults.BUILT_IN_WATCHTOWER to "watchtower",
    NavItemsConfigDefaults.BUILT_IN_SETTINGS to "settings",
)

/**
 * The predefined cipher-filter icons (the singleton `ImageVector` accessors
 * `CipherFilterRepositoryImpl` assigns), keyed by identity → a stable hint
 * Swift maps to an SF Symbol. User-saved filters have no icon → `"filter"`.
 */
private val filterIconHints: Map<ImageVector, String> by lazy {
    mapOf(
        DSecret.Type.Login.iconImageVector() to "login",
        DSecret.Type.Card.iconImageVector() to "card",
        DSecret.Type.Identity.iconImageVector() to "identity",
        DSecret.Type.SecureNote.iconImageVector() to "note",
        DSecret.Type.SshKey.iconImageVector() to "sshKey",
        DSecret.Type.GpgKey.iconImageVector() to "gpgKey",
        Icons.Outlined.KeyguardTwoFa to "otp",
    )
}

private fun iconHint(ref: NavItemRef, icon: ImageVector): String = when (ref) {
    is NavItemRef.CipherFilter -> filterIconHints[icon] ?: "filter"
    else -> ""
}

private fun HomeNavigationItem.toSectionSnapshot(): NavSectionSnapshot {
    val ref = spec.ref
    return when (ref) {
        is NavItemRef.CipherFilter -> NavSectionSnapshot(
            key = key,
            isCipherFilter = true,
            // stackId is already "cipher_filter:<id>".
            scope = stackId,
            title = (label as? TextHolder.Value)?.data.orEmpty(),
            iconHint = filterIconHints[icon] ?: "filter",
            filterId = ref.id,
        )

        else -> NavSectionSnapshot(
            key = key,
            isCipherFilter = false,
            scope = builtInScopes[key] ?: key,
            title = "",
            iconHint = "",
            filterId = "",
        )
    }
}
