package com.artemchep.keyguard.feature.loading

import arrow.core.Either
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class LoadingTask(
    private val translator: TranslatorScope,
    private val scope: CoroutineScope,
    /**
     * Exception handler that's responsible for parsing the
     * error messages as user-readable messages.
     */
    private val exceptionHandler: (Throwable) -> String = { e ->
        getErrorReadableMessage(e, translator)
    },
) {
    private val isWorkingSink = MutableStateFlow(false)

    private val errorSink = EventFlow<Failure>()

    val isExecutingFlow get() = isWorkingSink

    val errorFlow: Flow<String> = errorSink.map { it.text }

    data class Failure(
        val tag: String?,
        val text: String,
    )

    /**
     * Executes given task if the manager is
     * not working, otherwise skips it.
     */
    fun execute(
        io: IO<*>,
        tag: String? = null,
    ) {
        scope.launch {
            if (isWorkingSink.value) {
                return@launch
            }
            isWorkingSink.value = true
            try {
                val result = io.attempt().bind()
                if (result is Either.Left<Throwable>) {
                    val message = Failure(
                        tag = tag,
                        text = exceptionHandler(result.value),
                    )
                    result.value.printStackTrace()
                    errorSink.emit(message)
                } else {
                    // Normally executing a task navigates the user somewhere. We
                    // artificially slow down the execution of the task, so the app state
                    // changes before the user is able to interact with the button again.
                    delay(60L)
                }
            } finally {
                isWorkingSink.value = false
            }
        }
    }
}

fun getErrorReadableMessage(e: Throwable, translator: TranslatorScope) =
    when (e) {
        is Readable -> translator.translate(e.title)
        else -> e.message.orEmpty()
    }
