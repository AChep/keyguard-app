package com.artemchep.keyguard.apple.applink

import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.applink.AppLinkState
import com.artemchep.keyguard.feature.applink.AppLinkUseCases
import com.artemchep.keyguard.feature.applink.appLinkStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.platform.LeContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.koin.core.scope.Scope

/**
 * The linked devices settings, driven by the shared Compose producer: whether this app
 * is linked to the apps on the user's other devices, this device, and what it syncs with
 * them. Renaming asks through the dialog bridge. Swift opens the pairing screens, the
 * connection screen and the documentation itself. Main-confined.
 */
class AppLinkSession internal constructor(
    private val ctx: CoreContext,
) {
    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private val session = DetailSession<AppLinkSnapshot, AppLinkActions>()

    /** Emits [AppLinkSnapshot.empty] while the vault is locked. */
    fun observe(
        onChange: (AppLinkSnapshot) -> Unit,
    ): KeyguardCancellable = session.observe(onChange) { publish ->
        ctx.launchSessionObserver(
            onLocked = { publish(AppLinkSnapshot.empty, AppLinkActions(null)) },
        ) { state ->
            val sessionKoin = state.sessionKoin
            val leContext = ctx.koin.get<LeContext>()
            ctx.koin
                .newHeadlessStateFlowScope(
                    "app_link",
                    this,
                    navigationInterceptorProvider?.invoke(sessionKoin),
                )
                .appLinkStateProducer(
                    useCases = AppLinkUseCases(
                        getAppLinkState = sessionKoin.get(),
                        getAppLinkSyncCategories = sessionKoin.get(),
                        putAppLinkSyncCategory = sessionKoin.get(),
                        getAppLinkThisDevice = sessionKoin.get(),
                        putAppLinkDeviceLabel = sessionKoin.get(),
                        getPurchased = sessionKoin.get(),
                    ),
                    confirmationRouteFactory = sessionKoin.get(),
                )
                .mapNotNull { it.getOrNull() }
                .throttleLatest()
                .map { input ->
                    buildSnapshot(input, leContext) to AppLinkActions(input)
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

    fun close() = session.close()

    private suspend fun buildSnapshot(
        state: AppLinkState,
        leContext: LeContext,
    ): AppLinkSnapshot {
        val unlinked = state.content as? AppLinkState.Content.Unlinked
        return AppLinkSnapshot(
            loaded = true,
            linked = unlinked == null,
            premiumRequired = state.premiumRequired,
            canLink = unlinked?.linkActions?.isNotEmpty() == true,
            deviceLabel = state.thisDevice.label,
            relayText = state.thisDevice.relayText,
            lanText = state.thisDevice.lanText,
            categories = state.categories.map { category ->
                AppLinkSnapshot.Category(
                    key = category.category.name,
                    title = textResource(category.title, leContext),
                    checked = category.checked,
                    enabled = category.onCheckedChange != null,
                )
            },
        )
    }

    /** The screen's callbacks, published atomically with the snapshot built from the same [state]. */
    private class AppLinkActions(val state: AppLinkState?) {
        fun category(key: String) = state
            ?.categories
            ?.firstOrNull { it.category.name == key }
    }

    /** Turns syncing the category [key] on this device on or off. */
    fun setCategory(key: String, value: Boolean) = session.withActions { actions ->
        actions.category(key)?.onCheckedChange?.invoke(value)
    }

    /** Asks for a new name of this device. */
    fun rename() = session.withActions { actions ->
        actions.state?.thisDevice?.onRename?.invoke()
    }
}
