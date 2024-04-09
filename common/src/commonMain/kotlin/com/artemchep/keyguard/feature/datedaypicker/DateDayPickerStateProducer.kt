package com.artemchep.keyguard.feature.datedaypicker

import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

@Composable
fun produceDatePickerState(
    args: DateDayPickerRoute.Args,
    transmitter: RouteResultTransmitter<DateDayPickerResult>,
): Loadable<DateDayPickerState> = produceScreenState(
    key = "date_day_picker",
    initial = Loadable.Loading,
    args = arrayOf(),
) {
    val initialDate = args.initialDate
        ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val initialDateMs = initialDate
        .atStartOfDayIn(TimeZone.UTC)
        .toEpochMilliseconds()

    val dateSink = mutablePersistedFlow<LocalDate?>("date") {
        initialDate
    }

    val selectableYears = args.selectableDates
        ?.let { dates -> dates.start.year..dates.endInclusive.year }
        ?: DatePickerDefaults.YearRange
    val selectableDates = args.selectableDates
        ?.let { dates ->
            val times = kotlin.run {
                val start = dates.start.atStartOfDayIn(TimeZone.UTC)
                val end = dates.endInclusive
                    .plus(1, DateTimeUnit.DAY)
                    .atStartOfDayIn(TimeZone.UTC)
                    .minus(1, DateTimeUnit.MILLISECOND)
                start.toEpochMilliseconds()..end.toEpochMilliseconds()
            }
            object : SelectableDates {
                override fun isSelectableDate(
                    utcTimeMillis: Long,
                ): Boolean = utcTimeMillis in times

                override fun isSelectableYear(year: Int): Boolean = year in selectableYears
            }
        }
        ?: DatePickerDefaults.AllDates
    val content = DateDayPickerState.Content(
        initialDateMs = initialDateMs,
        selectableYears = selectableYears,
        selectableDates = selectableDates,
        onSelect = dateSink::value::set,
    )
    dateSink
        .map { date ->
            val state = DateDayPickerState(
                content = content,
                onDeny = {
                    transmitter(DateDayPickerResult.Deny)
                    navigatePopSelf()
                },
                onConfirm = if (date != null) {
                    // lambda
                    {
                        val result = DateDayPickerResult.Confirm(
                            localDate = date,
                        )
                        transmitter(result)
                        navigatePopSelf()
                    }
                } else {
                    null
                },
            )
            Loadable.Ok(state)
        }
}
