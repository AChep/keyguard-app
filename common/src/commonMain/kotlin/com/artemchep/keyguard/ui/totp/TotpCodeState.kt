package com.artemchep.keyguard.ui.totp

import com.artemchep.keyguard.common.model.TotpCode
import com.artemchep.keyguard.common.model.TotpToken
import com.artemchep.keyguard.common.usecase.GetTotpCodeWithOffset
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.math.roundToInt
import kotlin.time.Clock

sealed interface TotpCodeState {
    data object Loading : TotpCodeState

    data object Error : TotpCodeState

    data class Success(
        val codes: PersistentList<PersistentList<String>>,
        val codeRaw: String,
        val counter: Counter,
    ) : TotpCodeState {
        sealed interface Counter {
            val text: String
        }

        data class TimeBasedCounter(
            val time: String,
            val progress: Float,
        ) : Counter {
            override val text: String
                get() = time
        }

        data class IncrementBasedCounter(
            val counter: String,
        ) : Counter {
            override val text: String
                get() = counter
        }
    }
}

/**
 * Drives the [GetTotpCodeWithOffset] use-case and emits a [TotpCodeState] that
 * stays current: for a time-based token it re-emits on every whole-second
 * boundary so the countdown ticks, for a counter-based (HOTP) token it emits a
 * single value. The flow does not emit [TotpCodeState.Loading] itself — callers
 * provide that as the initial value (Compose via `collectAsState(initial = …)`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun totpCodeFlow(
    getTotpCode: GetTotpCodeWithOffset,
    totpToken: TotpToken,
    offset: Int = 0,
): Flow<TotpCodeState> = getTotpCode(totpToken, offset)
    .flatMapLatest { result ->
        val totpCode = result.getOrNull()
            ?: return@flatMapLatest flowOf(TotpCodeState.Error)
        // Format the totp code, so it's easier to read for the user.
        val codes = totpCode.formatCode2()
        when (val counter = totpCode.counter) {
            is TotpCode.TimeBasedCounter -> flow {
                while (true) {
                    val now = Clock.System.now()
                    val remainingDuration = counter.expiration - now

                    val time = remainingDuration
                        .inWholeMilliseconds
                        .toFloat()
                        .div(1000F)
                        .roundToInt()
                        .coerceAtLeast(0)
                        .toString()
                    val progress = counter.remainingProgressAt(now)
                    emit(
                        TotpCodeState.Success(
                            codes = codes,
                            codeRaw = totpCode.code,
                            counter = TotpCodeState.Success.TimeBasedCounter(
                                time = time,
                                progress = progress,
                            ),
                        ),
                    )

                    // Re-emit on the next whole-second boundary.
                    val dt = remainingDuration
                        .inWholeMilliseconds
                        .rem(1000L)
                    delay(dt + 1L)
                }
            }

            is TotpCode.IncrementBasedCounter -> flowOf(
                TotpCodeState.Success(
                    codes = codes,
                    codeRaw = totpCode.code,
                    counter = TotpCodeState.Success.IncrementBasedCounter(
                        counter = counter.counter.toString(),
                    ),
                ),
            )
        }
    }
