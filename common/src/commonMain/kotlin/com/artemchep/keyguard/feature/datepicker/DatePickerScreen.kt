package com.artemchep.keyguard.feature.datepicker

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.dialog.dialogArrowNavigation
import com.artemchep.keyguard.feature.dialog.dialogTabTargets
import com.artemchep.keyguard.feature.dialog.rememberInitialItemFocus
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.selectedContainer
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList

@Composable
fun DatePickerScreen(
    args: DatePickerRoute.Args,
    transmitter: RouteResultTransmitter<DatePickerResult>,
) {
    val loadableState = produceDatePickerState(
        args = args,
        transmitter = transmitter,
    )
    DatePickerDialog(loadableState)
}

@Composable
internal fun DatePickerDialog(loadableState: Loadable<DatePickerState>) {
    val close = remember { FocusRequester() }
    val ok = remember { FocusRequester() }
    Dialog(
        icon = icon(Icons.Outlined.CalendarMonth),
        title = {
            Text(stringResource(Res.string.datepicker_title))
        },
        content = {
            Column {
                val state = loadableState.getOrNull()
                    ?: return@Column
                Content(
                    state = state,
                    close = close,
                    ok = ok,
                )
            }
        },
        contentScrollable = false,
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onDeny)
            val updatedOnOk by rememberUpdatedState(loadableState.getOrNull()?.onConfirm)
            TextButton(
                modifier = Modifier.focusRequester(close),
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
            TextButton(
                modifier = Modifier.focusRequester(ok),
                enabled = updatedOnOk != null,
                onClick = {
                    updatedOnOk?.invoke()
                },
            ) {
                Text(stringResource(Res.string.ok))
            }
        },
    )
}

@Composable
private fun ColumnScope.Content(
    state: DatePickerState,
    close: FocusRequester,
    ok: FocusRequester,
) {
    DatePickerHeader(state.content)
    val monthList = remember { FocusRequester() }
    val yearList = remember { FocusRequester() }
    Row(
        modifier = Modifier,
    ) {
        MonthPicker(
            modifier = Modifier
                .weight(1f),
            listFocus = monthList,
            previousGroup = ok,
            nextGroup = yearList,
            initiallyFocused = true,
            month = state.content.month,
            months = state.content.months,
        )
        MonthPicker(
            modifier = Modifier
                .weight(1f),
            listFocus = yearList,
            previousGroup = monthList,
            nextGroup = close,
            initiallyFocused = false,
            month = state.content.year,
            months = state.content.years,
        )
    }
}

@Composable
private fun DatePickerHeader(content: DatePickerState.Content) {
    Surface(
        modifier = Modifier
            .padding(
                horizontal = 8.dp,
                vertical = 8.dp,
            ),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val monthRes = getMonthTitleStringRes(content.month)
            val year = getYearTitle(content.year)
            Text(
                modifier = Modifier
                    .animateContentSize()
                    .alignByBaseline(),
                text = stringResource(monthRes),
                style = MaterialTheme.typography.displaySmall,
            )
            Text(
                modifier = Modifier
                    .animateContentSize()
                    .alignByBaseline(),
                text = year,
                style = MaterialTheme.typography.displayMedium,
            )
        }
    }
}

@Composable
private fun MonthPicker(
    modifier: Modifier = Modifier,
    listFocus: FocusRequester,
    previousGroup: FocusRequester,
    nextGroup: FocusRequester,
    initiallyFocused: Boolean,
    month: Int,
    months: ImmutableList<DatePickerState.Item>,
) {
    val initialIndex = remember { months.indexOfFirst { it.key == month }.coerceAtLeast(0) }
    // Keep the item before the selection in view.
    val contextIndex = (initialIndex - 1).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = contextIndex)
    val initialFocus = listState.rememberInitialItemFocus(
        index = initialIndex.takeIf { initiallyFocused && months.isNotEmpty() },
        contextIndex = contextIndex,
    )
    LazyColumn(
        modifier = modifier.focusRequester(listFocus)
            .dialogArrowNavigation()
            .dialogTabTargets(previousGroup, nextGroup),
        state = listState,
    ) {
        itemsIndexed(
            items = months,
            key = { _, item -> item.key },
        ) { index, item ->
            val selected = month == item.key
            MonthPickerItem(
                modifier = Modifier
                    .then(if (index == initialIndex) Modifier.focusRequester(initialFocus) else Modifier)
                    .semantics { this.selected = selected },
                item = item,
                selected = selected,
            )
        }
    }
}

@Composable
private fun MonthPickerItem(
    modifier: Modifier = Modifier,
    item: DatePickerState.Item,
    selected: Boolean,
) {
    val backgroundColor = if (selected) {
        MaterialTheme.colorScheme.selectedContainer
    } else {
        Color.Unspecified
    }
    FlatItem(
        modifier = modifier,
        backgroundColor = backgroundColor,
        title = {
            Row {
                Text(text = item.title)

                val index = item.index
                if (!index.isNullOrEmpty()) {
                    Text(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .alignByBaseline(),
                        text = index,
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                }
            }
        },
        onClick = item.onClick,
    )
}
