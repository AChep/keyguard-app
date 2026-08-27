package com.artemchep.keyguard.feature.passwordmemory

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.auth.common.TextCell
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_incorrect_password
import com.artemchep.keyguard.res.password_memory_test_success
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

@Composable
internal fun producePasswordMemoryState(
    args: PasswordMemoryRoute.Args,
): PasswordMemoryState = produceScreenState(
    key = "password_memory",
    initial = PasswordMemoryState(),
) {
    passwordMemoryStateProducer(
        args = args,
    )
}

internal suspend fun RememberStateFlowScope.passwordMemoryStateProducer(
    args: PasswordMemoryRoute.Args,
): Flow<PasswordMemoryState> = passwordMemoryStateFlow(
    expectedPassword = args.password,
    incorrectPassword = translate(Res.string.error_incorrect_password),
    passwordMatches = translate(Res.string.password_memory_test_success),
    onMessage = ::message,
    onClose = ::navigatePopSelf,
)

internal fun passwordMemoryStateFlow(
    expectedPassword: String,
    incorrectPassword: String,
    passwordMatches: String,
    onMessage: (ToastMessage) -> Unit,
    onClose: () -> Unit,
): Flow<PasswordMemoryState> {
    val formSink = MutableStateFlow(PasswordMemoryForm())

    fun onVerify() {
        val form = formSink.value
        if (!form.canVerify) return

        val verified = form.verify(expectedPassword)
        formSink.value = verified
        if (verified.result == PasswordMemoryResult.Correct) {
            onMessage(
                ToastMessage(
                    title = passwordMatches,
                    type = ToastMessage.Type.SUCCESS,
                ),
            )
            onClose()
        }
    }

    return formSink
        .map { form ->
            val error = incorrectPassword
                .takeIf { form.result == PasswordMemoryResult.Incorrect }
            val password = TextFieldModel(
                id = "password",
                text = form.cell.text,
                textRevision = form.cell.revision,
                error = error,
                onChange = { value ->
                    formSink.update { current ->
                        current.withPassword(value)
                    }
                },
            )
            PasswordMemoryState(
                password = password,
                onVerify = ::onVerify.takeIf { form.canVerify },
                onClose = onClose,
            )
        }
}

internal enum class PasswordMemoryResult {
    Correct,
    Incorrect,
}

internal data class PasswordMemoryForm(
    val cell: TextCell = TextCell(text = ""),
    val result: PasswordMemoryResult? = null,
) {
    val canVerify: Boolean
        get() = cell.text.isNotEmpty() && result != PasswordMemoryResult.Correct

    fun withPassword(password: String): PasswordMemoryForm = copy(
        cell = cell.copy(text = password),
        result = null,
    )

    fun verify(expectedPassword: String): PasswordMemoryForm = copy(
        result = evaluatePasswordMemoryAttempt(
            expectedPassword = expectedPassword,
            attemptedPassword = cell.text,
        ),
    )
}

internal fun evaluatePasswordMemoryAttempt(
    expectedPassword: String,
    attemptedPassword: String,
): PasswordMemoryResult = if (attemptedPassword == expectedPassword) {
    PasswordMemoryResult.Correct
} else {
    PasswordMemoryResult.Incorrect
}
