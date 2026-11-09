package com.artemchep.keyguard.apple.applink

import com.artemchep.keyguard.apple.auth.setAllowLan
import com.artemchep.keyguard.apple.auth.setRelayStorage
import com.artemchep.keyguard.apple.auth.toSnapshot
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.DetailSession
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.applink.connection.AppLinkConnectionState
import com.artemchep.keyguard.feature.applink.connection.appLinkConnectionStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/**
 * The network settings of this device, driven by the shared Compose producer. Saving
 * closes the screen, as on Compose: the session completes. Main-confined.
 */
class AppLinkConnectionSession internal constructor(
    private val ctx: CoreContext,
) {
    internal var navigationInterceptorProvider: ((Scope) -> ((NavigationIntent) -> Boolean))? = null

    private val session = DetailSession<AppLinkConnectionSnapshot, AppLinkConnectionActions>()

    /** Completes once the settings are saved, or the vault is locked. */
    fun observe(
        onChange: (AppLinkConnectionSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable = session.observe(onChange, onClose) { publish, finish ->
        ctx.launchSessionObserver(onLocked = finish) { state ->
            val sessionKoin = state.sessionKoin
            val interceptor = navigationInterceptorProvider?.invoke(sessionKoin)
            ctx.koin
                .newHeadlessStateFlowScope(
                    "app_link_connection",
                    this,
                    navigationInterceptor = { intent ->
                        if (intent is NavigationIntent.Pop || intent is NavigationIntent.PopById) {
                            ctx.scope.launch { finish() }
                            true
                        } else {
                            interceptor?.invoke(intent) ?: false
                        }
                    },
                )
                .appLinkConnectionStateProducer(
                    getAppLinkThisDevice = sessionKoin.get(),
                    putAppLinkNetwork = sessionKoin.get(),
                )
                .mapNotNull { it.getOrNull() }
                .throttleLatest()
                .map { input ->
                    val actions = AppLinkConnectionActions(input)
                    buildSnapshot(input, actions.fields) to actions
                }
                .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
        }
    }

    fun close() = session.close()

    private fun buildSnapshot(
        state: AppLinkConnectionState,
        fieldHandlers: MutableMap<String, (String) -> Unit>,
    ) = AppLinkConnectionSnapshot(
        loaded = true,
        network = state.network.toSnapshot(fieldHandlers),
        canSave = state.onSave != null,
        isSaving = state.isSaving,
    )

    /** The screen's callbacks, published atomically with the snapshot built from the same [state]. */
    private class AppLinkConnectionActions(val state: AppLinkConnectionState?) {
        val fields = LinkedHashMap<String, (String) -> Unit>()
    }

    /** Types into the field [id] of the [AppLinkConnectionSnapshot.network]. */
    fun setNetworkField(id: String, text: String) = session.withActions { it.fields[id]?.invoke(text) }

    fun setAllowLan(value: Boolean) = session.withActions { it.state?.network?.setAllowLan(value) }

    fun setRelayStorage(value: Boolean) = session.withActions { it.state?.network?.setRelayStorage(value) }

    /** Saves the settings; the session completes once they are saved. */
    fun save() = session.withActions { it.state?.onSave?.invoke() }
}
