package com.artemchep.keyguard.presentation.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val FEEDBACK_EMAIL = "artemchep+keyguard@gmail.com"
private const val TEST_CRASH_MESSAGE = "send test crash report"

/** An atomic text snapshot supplied by the host's canonical field store. */
data class FeedbackTextState(
    val id: String,
    val text: String,
    val revision: Int = 0,
)

data class FeedbackMessageState(
    val id: String,
    val text: String,
    val revision: Int,
    val error: FeedbackValidationError? = null,
)

enum class FeedbackValidationError {
    MUST_NOT_BE_BLANK,
}

sealed interface FeedbackEffect {
    data class SendEmail(
        val email: String,
        val body: String,
    ) : FeedbackEffect
}

data class FeedbackState(
    val message: FeedbackMessageState,
    val onMessageChange: (String) -> Unit,
    val onSetMessage: (String) -> Unit,
    val onClear: (() -> Unit)? = null,
    val onSendClick: (() -> Unit)? = null,
)

/**
 * Produces feedback form state without owning storage or depending on a UI toolkit.
 *
 * Text and revision arrive together through [messageFlow]. The host keeps one canonical field
 * store and supplies its existing user-edit and command paths through [onMessageChange] and
 * [onSetMessage].
 */
fun feedbackStateProducer(
    messageFlow: Flow<FeedbackTextState>,
    onMessageChange: (String) -> Unit,
    onSetMessage: (String) -> Unit,
    onEffect: (FeedbackEffect) -> Unit,
): Flow<FeedbackState> = messageFlow.map { message ->
    val normalizedMessage = message.text.trim(' ')
    val error = if (normalizedMessage.isBlank()) {
        FeedbackValidationError.MUST_NOT_BE_BLANK
    } else {
        null
    }
    FeedbackState(
        message = FeedbackMessageState(
            id = message.id,
            text = message.text,
            revision = message.revision,
            error = error,
        ),
        onMessageChange = onMessageChange,
        onSetMessage = onSetMessage,
        onClear = if (normalizedMessage.isNotEmpty()) {
            {
                onSetMessage("")
            }
        } else {
            null
        },
        onSendClick = if (error == null) {
            {
                if (normalizedMessage == TEST_CRASH_MESSAGE) {
                    @Suppress("TooGenericExceptionThrown")
                    throw RuntimeException("Test crash report.")
                }
                onEffect(
                    FeedbackEffect.SendEmail(
                        email = FEEDBACK_EMAIL,
                        body = normalizedMessage,
                    ),
                )
            }
        } else {
            null
        },
    )
}
