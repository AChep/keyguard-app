package com.artemchep.keyguard.ui.time

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.artemchep.keyguard.common.util.flowOfTime
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration

@Composable
fun rememberLocalizedRelativeTime(
    instant: Instant,
): String {
    val relativeTimeState = remember(instant) {
        flowOfTime()
            .map { now ->
                RelativeTime.ofNow(
                    instant = instant,
                    now = now,
                )
            }
            .distinctUntilChanged()
    }.collectAsState(initial = RelativeTime.ofNow(instant))
    return when (val value = relativeTimeState.value) {
        is RelativeTime.JustNow -> {
            stringResource(Res.string.relative_time_just_now_short)
        }

        is RelativeTime.Ago -> {
            val n = value.value.toString()
            when (value.unit) {
                RelativeTime.TimeUnit.MINUTE -> stringResource(
                    Res.string.relative_time_minute_short,
                    n,
                )
                RelativeTime.TimeUnit.HOUR -> stringResource(
                    Res.string.relative_time_hour_short,
                    n,
                )
                RelativeTime.TimeUnit.DAY -> stringResource(
                    Res.string.relative_time_day_short,
                    n,
                )
                RelativeTime.TimeUnit.WEEK -> stringResource(
                    Res.string.relative_time_week_short,
                    n,
                )
            }
        }
    }
}

private sealed interface RelativeTime {
    companion object {
        fun ofNow(
            instant: Instant,
            now: Instant = Clock.System.now(),
        ): RelativeTime {
            return (now - instant).toRelativeTime()
        }
    }

    enum class TimeUnit {
        MINUTE,
        HOUR,
        DAY,
        WEEK,
    }

    @Immutable
    data object JustNow : RelativeTime

    @Immutable
    data class Ago(
        val unit: TimeUnit,
        val value: Long,
    ) : RelativeTime
}

private fun Duration.toRelativeTime(): RelativeTime {
    val days = inWholeDays
    if (days > 0) {
        if (days >= 7) {
            val weeks = days / 7
            return RelativeTime.Ago(
                unit = RelativeTime.TimeUnit.WEEK,
                value = weeks,
            )
        }

        return RelativeTime.Ago(
            unit = RelativeTime.TimeUnit.DAY,
            value = days,
        )
    }
    val hours = inWholeHours
    if (hours > 0) {
        return RelativeTime.Ago(
            unit = RelativeTime.TimeUnit.HOUR,
            value = hours,
        )
    }
    val minutes = inWholeMinutes
    if (minutes > 0) {
        return RelativeTime.Ago(
            unit = RelativeTime.TimeUnit.MINUTE,
            value = minutes,
        )
    }

    return RelativeTime.JustNow
}
