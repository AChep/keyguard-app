package com.artemchep.keyguard.apple.settings

import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.feedback.FeedbackState
import com.artemchep.keyguard.feature.feedback.feedbackScreenStateProducer
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.toMailtoUrl
import kotlinx.coroutines.CoroutineScope

/** A flat, Swift-facing projection of the shared [FeedbackState]. */
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
 * The "Contact us" (feedback) screen. Runs the shared [feedbackScreenStateProducer]
 * headlessly and projects it to a flat [FeedbackSnapshot]. The single message field
 * and the "Send" action are routed per-instance by the navigation stack (the live
 * [FeedbackState] is handed back so input/submit reach the producer's closures).
 * Sending emits [NavigationIntent.NavigateToEmail], which the stack interceptor turns
 * into a `mailto:` open via the host's open-url handler.
 *
 * The producer needs no session-scoped use cases (only the core screen-state storage),
 * so it runs on [ctx].di rather than the session DI.
 */
internal class FeedbackController(
    private val ctx: CoreContext,
) {
    private var latestFeedbackState: FeedbackState? = null

    /**
     * Standalone observation for the native "Contact us" modal sheet (the macOS
     * Settings → About row, presented as a `.sheet` like its siblings rather than
     * pushed onto the navigation stack). Runs the same headless producer as the
     * stacked entry via [produceFeedbackInto]; the send button emits
     * [NavigationIntent.NavigateToEmail], routed to [openUrl] as a `mailto:` so the
     * host opens mail natively. The producer never pops itself, so the sheet is
     * dismissed purely by SwiftUI.
     */
    fun observeFeedback(
        onChange: (FeedbackSnapshot) -> Unit,
        openUrl: (String) -> Unit,
    ): KeyguardCancellable {
        val interceptor: (NavigationIntent) -> Boolean = { intent ->
            if (intent is NavigationIntent.NavigateToEmail) {
                openUrl(intent.toMailtoUrl())
                true
            } else {
                false
            }
        }
        return ctx.launchObserver {
            produceFeedbackInto(this, interceptor) { snapshot, state ->
                latestFeedbackState = state
                onChange(snapshot)
            }
        }
    }

    /** Writes [text] into the standalone feedback sheet's message field. */
    fun setFeedbackMessage(text: String) {
        latestFeedbackState?.message?.onChange?.invoke(text)
    }

    /** Submits the standalone feedback sheet (fires the send → NavigateToEmail). */
    fun submitFeedback() {
        latestFeedbackState?.onSendClick?.invoke()
    }

    suspend fun produceFeedbackInto(
        scope: CoroutineScope,
        interceptor: (NavigationIntent) -> Boolean,
        publish: suspend (FeedbackSnapshot, FeedbackState?) -> Unit,
    ) {
        val producerFlow = ctx.koin.newHeadlessStateFlowScope("feedback", scope, interceptor)
            .feedbackScreenStateProducer()
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
