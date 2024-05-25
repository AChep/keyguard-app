package com.artemchep.keyguard.feature.add

import com.artemchep.keyguard.common.usecase.DateFormatter
import com.artemchep.keyguard.feature.auth.common.SwitchFieldModel
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.datedaypicker.DateDayPickerResult
import com.artemchep.keyguard.feature.datedaypicker.DateDayPickerRoute
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.timepicker.TimePickerResult
import com.artemchep.keyguard.feature.timepicker.TimePickerRoute
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

context(RememberStateFlowScope)
suspend fun <Request> AddStateItem.Switch.Companion.produceItemFlow(
    key: String,
    initialValue: Boolean,
    populator: Request.(SwitchFieldModel) -> Request,
    factory: suspend (String, LocalStateItem<SwitchFieldModel, Request>) -> AddStateItem.Switch<Request>,
): AddStateItem.Switch<Request> {
    val sink = mutablePersistedFlow(key) { initialValue }
    val stateItem = LocalStateItem<SwitchFieldModel, Request>(
        flow = sink
            .map { value ->
                val model = SwitchFieldModel(
                    checked = value,
                    onChange = sink::value::set,
                )
                model
            }
            .stateIn(
                scope = screenScope,
            ),
        populator = populator,
    )
    return factory(key, stateItem)
}

context(RememberStateFlowScope)
suspend fun <Request> AddStateItem.DateTime.Companion.produceItemFlow(
    key: String,
    initialValue: LocalDateTime?,
    selectableDates: ClosedRange<LocalDate>? = null,
    dateFormatter: DateFormatter,
    badge: ((LocalDateTime) -> TextFieldModel2.Vl?)? = null,
    populator: Request.(AddStateItem.DateTime.State) -> Request,
    factory: (String, LocalStateItem<AddStateItem.DateTime.State, Request>) -> AddStateItem.DateTime<Request>,
): AddStateItem.DateTime<Request> {
    val now = initialValue
        ?: Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val localDateTimeSink = mutablePersistedFlow("$key.datetime") {
        now
    }
    val stateItem = LocalStateItem<AddStateItem.DateTime.State, Request>(
        flow = localDateTimeSink
            .map { localDateTime ->
                val localDateFormatted = dateFormatter.formatDateMedium(localDateTime.date)
                val localTimeFormatted = dateFormatter.formatTimeShort(localDateTime.time)
                AddStateItem.DateTime.State(
                    value = localDateTime,
                    date = localDateFormatted,
                    time = localTimeFormatted,
                    badge = badge?.invoke(localDateTime),
                    onSelectDate = {
                        val route = registerRouteResultReceiver(
                            DateDayPickerRoute(
                                DateDayPickerRoute.Args(
                                    initialDate = localDateTime.date,
                                    selectableDates = selectableDates,
                                ),
                            ),
                        ) { result ->
                            if (result is DateDayPickerResult.Confirm) {
                                localDateTimeSink.update {
                                    LocalDateTime(
                                        date = result.localDate,
                                        time = localDateTime.time,
                                    )
                                }
                            }
                        }
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                    onSelectTime = {
                        val route = registerRouteResultReceiver(
                            TimePickerRoute(
                                TimePickerRoute.Args(
                                    initialTime = localDateTime.time,
                                ),
                            ),
                        ) { result ->
                            if (result is TimePickerResult.Confirm) {
                                localDateTimeSink.update {
                                    LocalDateTime(
                                        date = localDateTime.date,
                                        time = result.localTime,
                                    )
                                }
                            }
                        }
                        val intent = NavigationIntent.NavigateToRoute(route)
                        navigate(intent)
                    },
                )
            }
            .stateIn(screenScope),
        populator = populator,
    )
    return factory(key, stateItem)
}
