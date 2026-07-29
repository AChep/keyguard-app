package com.artemchep.keyguard.feature.loading

import arrow.core.Either
import com.artemchep.keyguard.common.exception.Readable
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.feature.navigation.state.translate
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoadingTask(
    private val translator: TranslatorScope,
    private val scope: CoroutineScope,
    /**
     * Exception handler that's responsible for parsing the
     * error messages as user-readable messages.
     */
    private val exceptionHandler: suspend (Throwable) -> ReadableExceptionMessage = { e ->
        getErrorReadableMessage(e, translator)
    },
) {
    private val isWorkingSink = MutableStateFlow(false)

    private val errorSink = EventFlow<Failure>()

    val isExecutingFlow: StateFlow<Boolean> = isWorkingSink.asStateFlow()

    val errorFlow: Flow<Failure> = errorSink

    data class Failure(
        val tag: String?,
        val title: String,
        val text: String?,
    )

    /**
     * Executes given task if the manager is
     * not working, otherwise skips it.
     *
     * Returns `true` when this call claimed the executor and scheduled the
     * task, or `false` when another task already owns it.
     */
    fun execute(
        io: IO<*>,
        tag: String? = null,
    ): Boolean {
        if (!isWorkingSink.compareAndSet(expect = false, update = true)) {
            return false
        }
        val job = scope.launch {
            val result = io.attempt().bind()
            if (result is Either.Left<Throwable>) {
                val parsedMessage = exceptionHandler(result.value)
                val message = Failure(
                    tag = tag,
                    title = parsedMessage.title,
                    text = parsedMessage.text,
                )
                result.value.printStackTrace()
                errorSink.emit(message)
            } else {
                // Normally executing a task navigates the user somewhere. We
                // artificially slow down the execution of the task, so the app state
                // changes before the user is able to interact with the button again.
                delay(60L)
            }
        }
        // A default-start coroutine may be cancelled before its body executes,
        // so releasing from the body alone can leave the executor stuck. A job
        // completion handler also covers that path and is invoked exactly once.
        job.invokeOnCompletion {
            isWorkingSink.value = false
        }
        return true
    }
}

data class ReadableExceptionMessage(
    val title: String,
    val text: String? = null,
)

suspend fun getErrorReadableMessage(e: Throwable, translator: TranslatorScope) =
    when (e) {
        is Readable -> {
            val title = e.title.let { translator.translate(it) }
            val text = e.text?.let { translator.translate(it) }
            ReadableExceptionMessage(
                title = title,
                text = text,
            )
        }

        else -> {
            val title = e.message
                ?: translator.translate(Res.string.error_failed_unknown)
            ReadableExceptionMessage(
                title = title,
            )
        }
    }
