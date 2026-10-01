package com.artemchep.keyguard.apple.auth

import com.artemchep.keyguard.main
import com.artemchep.keyguard.common.usecase.MessageHub
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.platform.WindowId
import kotlinx.coroutines.launch

/**
 * The global toast / message bus consumer. The shared producers surface runtime
 * failures (a wrong master password, a failed create IO, …) through [MessageHub]
 * rather than into a screen state — mirroring the Compose `ToastComposable`.
 */
internal class MessagesController(
    private val ctx: CoreContext,
) {
    private val messageHub: MessageHub by lazy { ctx.koin.get() }

    /**
     * Observes the global toast / message bus and forwards each message as a flat
     * [MessageSnapshot]. The callback runs on the main thread.
     */
    fun observeMessages(
        onMessage: (MessageSnapshot) -> Unit,
    ): KeyguardCancellable {
        // Executor-surfaced errors carry no windowId, so MessageHub broadcasts
        // them to every consumer regardless of the id registered here.
        val unregister = messageHub.register(
            key = "macos",
            windowId = WindowId(0L),
        ) { message ->
            val snapshot = message.toMessageSnapshot()
            // copy() invokes this on whichever (background) thread emitted the
            // message; deliver on the main thread like every other observer.
            ctx.scope.launch { onMessage(snapshot) }
        }
        return KeyguardCancellable(onCancel = unregister)
    }
}
