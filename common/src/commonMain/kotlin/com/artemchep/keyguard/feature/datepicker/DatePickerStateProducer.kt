package com.artemchep.keyguard.feature.datepicker

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import dev.icerock.moko.resources.StringResource
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Clock
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.time.Year

@Composable
fun produceDatePickerState(
    args: DatePickerRoute.Args,
    transmitter: RouteResultTransmitter<DatePickerResult>,
): Loadable<DatePickerState> = produceScreenState(
    key = "date_picker",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val yearCurrent = getYear()
    val yearSink = mutablePersistedFlow("year") {
        val year = args.year
            ?: getYear()
        year
    }
    val yearItems = kotlin.run {
        val yearMin = yearCurrent - 32
        val yearMax = yearCurrent + 64
        (yearMin..yearMax)
            .map { year ->
                val title = getYearTitle(year)
                DatePickerState.Item(
                    key = year,
                    title = title,
                    onClick = {
                        yearSink.value = year
                    },
                )
            }
            .toImmutableList()
    }

    val monthSink = mutablePersistedFlow("month") {
        val month = args.month
            ?: Clock.System.now().toLocalDateTime(TimeZone.UTC).month.value
        month
    }
    val monthItems = kotlin.run {
        (1..12)
            .map { month ->
                val index = kotlin.run {
                    val m = month.toString().padStart(2, '0')
                    "($m)"
                }
                val titleRes = getMonthTitleStringRes(month)
                DatePickerState.Item(
                    key = month,
                    title = translate(titleRes),
                    index = index,
                    onClick = {
                        monthSink.value = month
                    },
                )
            }
            .toImmutableList()
    }

    combine(
        monthSink,
        yearSink,
    ) { month, year ->
        val content = DatePickerState.Content(
            month = month,
            months = monthItems,
            year = year,
            years = yearItems,
        )
        val state = DatePickerState(
            content = content,
            onDeny = {
                transmitter(DatePickerResult.Deny)
                navigatePopSelf()
            },
            onConfirm = {
                val result = DatePickerResult.Confirm(
                    month = Month.of(month),
                    year = Year.of(year),
                )
                transmitter(result)
                navigatePopSelf()
            },
        )
        Loadable.Ok(state)
    }
}

fun getMonthTitleStringRes(month: Int): StringResource = when (month) {
    1 -> Res.strings.january
    2 -> Res.strings.february
    3 -> Res.strings.march
    4 -> Res.strings.april
    5 -> Res.strings.may
    6 -> Res.strings.june
    7 -> Res.strings.july
    8 -> Res.strings.august
    9 -> Res.strings.september
    10 -> Res.strings.october
    11 -> Res.strings.november
    12 -> Res.strings.december
    else -> throw IllegalArgumentException("Number of the month should be in 1..12 range, got $month instead!")
}

fun getYearTitle(year: Int) = year.toString()

private fun getYear() = Clock.System.now().toLocalDateTime(TimeZone.UTC).year
