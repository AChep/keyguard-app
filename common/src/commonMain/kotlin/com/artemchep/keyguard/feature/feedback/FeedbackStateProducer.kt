package com.artemchep.keyguard.feature.feedback

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedFeedback
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
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

    fun onSend(message: String) {
        if (message == "send test crash report") {
            val msg = "Test crash report."
            throw RuntimeException(msg)
        }

        val subject = getFeedbackSubject()
        val intent = NavigationIntent.NavigateToEmail(
            email = "artemchep+keyguard@gmail.com",
            subject = subject,
            body = message,
        )
        navigate(intent)
    }

    return messageHandle.sink
        .map { cell ->
            val validatedMessage = validatedFeedback(cell.text)
            val canLogin = validatedMessage is Validated.Success
            val message = TextFieldModel.of(
                cell = cell,
                handle = messageHandle,
                validated = validatedMessage,
            )
            FeedbackState(
                message = message,
                onSendClick = if (canLogin) {
                    // lambda
                    ::onSend.partially1(validatedMessage.model)
                } else {
                    null
                },
                onClear = if (validatedMessage.model.isNotEmpty()) {
                    // lambda
                    {
                        // Command path: bumps the revision so the edit
                        // buffer adopts the cleared text.
                        messageHandle.setText("")
                    }
                } else {
                    null
                },
            )
        }
        .map { state ->
            Loadable.Ok(state)
        }
}
