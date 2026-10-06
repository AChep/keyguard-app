package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.feedback.FeedbackState
import com.artemchep.keyguard.feature.feedback.feedbackScreenStateProducer
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.launchOnMainWhileActive
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.toMailtoUrl
import kotlinx.coroutines.CoroutineScope

data class FeedbackSnapshot(
    val loaded: Boolean = false,
    val message: String = "",
    /** Bumps only on a programmatic write so the Swift buffer adopts it; see TextFieldModel. */
    val messageRevision: Int = 0,
    val error: String? = null,
    val canSend: Boolean = false,
) {
    companion object {
        val empty = FeedbackSnapshot()
    }
}

/**
 * In the navigation stack, input and Send are routed per instance: [produceFeedbackInto] hands back the live
 * [FeedbackState] so they reach the producer's closures. The producer needs no session-scoped use cases (only the
 * core screen-state storage), so it runs on `ctx.koin` (the root DI) rather than the session scope.
 */
internal class FeedbackController(
    private val ctx: CoreContext,
) {
    fun makeFeedbackSession(openUrl: (String) -> Unit): FeedbackSession = FeedbackSession { publish ->
        ctx.launchObserver {
            val interceptor: (NavigationIntent) -> Boolean = { intent ->
                if (intent is NavigationIntent.NavigateToEmail) {
                    launchOnMainWhileActive(ctx.scope) { openUrl(intent.toMailtoUrl()) }
                    true
                } else {
                    false
                }
            }
            produceFeedbackInto(this, interceptor) { snapshot, state ->
                publish(
                    snapshot,
                    FeedbackActions(
                        setMessage = state?.message?.onChange,
                        submit = state?.onSendClick,
                    ),
                )
            }
        }
    }

    suspend fun produceFeedbackInto(
        scope: CoroutineScope,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (FeedbackSnapshot, FeedbackState?) -> Unit,
    ) {
        val producerFlow = ctx.koin.newHeadlessStateFlowScope("feedback", scope, interceptor)
            .feedbackScreenStateProducer(messageStorage = PersistedStorage.InMemory)
        producerFlow.collectOnMain { loadable ->
            val state = loadable.getOrNull()
            val snapshot = if (state == null) {
                FeedbackSnapshot.empty
            } else {
                FeedbackSnapshot(
                    loaded = true,
                    message = state.message.text,
                    messageRevision = state.message.textRevision,
                    error = state.message.error,
                    canSend = state.onSendClick != null,
                )
            }
            publish(snapshot, state)
        }
    }
}
