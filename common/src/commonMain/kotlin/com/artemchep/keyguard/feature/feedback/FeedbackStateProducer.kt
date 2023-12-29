package com.artemchep.keyguard.feature.feedback

import androidx.compose.runtime.Composable
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.util.validatedFeedback
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.state.PersistedStorage
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.map

@Composable
fun produceFeedbackScreenState(): Loadable<FeedbackState> = produceScreenState(
    key = "feedback",
    initial = Loadable.Loading,
) {
    val storage = kotlin.run {
        val disk = loadDiskHandle("feedback")
        PersistedStorage.InDisk(disk)
    }
    val messageSink = mutablePersistedFlow(
        key = "message",
        storage = storage,
    ) { "" }
    val messageState = mutableComposeState(messageSink)

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

    val validatedMessageFlow = messageSink
        .validatedFeedback(this)
    validatedMessageFlow
        .map { validatedMessage ->
            val canLogin = validatedMessage is Validated.Success
            val message = TextFieldModel2.of(
                messageState,
                validatedMessage,
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
                        messageState.value = ""
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
