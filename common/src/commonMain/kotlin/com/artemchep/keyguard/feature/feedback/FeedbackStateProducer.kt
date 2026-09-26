package com.artemchep.keyguard.feature.feedback

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.presentation.feedback.FeedbackEffect
import com.artemchep.keyguard.presentation.feedback.FeedbackTextState
import com.artemchep.keyguard.presentation.feedback.FeedbackValidationError
import com.artemchep.keyguard.presentation.feedback.feedbackStateProducer
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_must_not_be_blank
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
fun produceFeedbackScreenState(): Loadable<FeedbackState> = produceScreenState(
    key = "feedback",
    initial = Loadable.Loading,
) {
    feedbackScreenStateProducer()
}

suspend fun RememberStateFlowScope.feedbackScreenStateProducer(): Flow<Loadable<FeedbackState>> {
    val storage = kotlin.run {
        val disk = loadDiskHandle("feedback")
        PersistedStorage.InDisk(disk)
    }
    val messageHandle = textFieldHandle(
        key = "message",
        storage = storage,
    )
    val producer = feedbackStateProducer(
        messageFlow = messageHandle.sink.map { cell ->
            FeedbackTextState(
                id = messageHandle.id,
                text = cell.text,
                revision = cell.revision,
            )
        },
        onMessageChange = messageHandle::onChange,
        onSetMessage = messageHandle::setText,
        onEffect = { effect ->
            when (effect) {
                is FeedbackEffect.SendEmail -> {
                    navigate(
                        NavigationIntent.NavigateToEmail(
                            email = effect.email,
                            subject = getFeedbackSubject(),
                            body = effect.body,
                        ),
                    )
                }
            }
        },
    )

    return producer
        .map { state ->
            val message = state.message
            val error = when (message.error) {
                FeedbackValidationError.MUST_NOT_BE_BLANK -> {
                    translate(Res.string.error_must_not_be_blank)
                }
                null -> null
            }
            FeedbackState(
                message = TextFieldModel(
                    id = message.id,
                    text = message.text,
                    textRevision = message.revision,
                    error = error?.takeUnless { message.text.isEmpty() },
                    onChange = state.onMessageChange,
                    onSetText = state.onSetMessage,
                ),
                onSendClick = state.onSendClick,
                onClear = state.onClear,
            )
        }
        .map { state ->
            Loadable.Ok(state)
        }
}
