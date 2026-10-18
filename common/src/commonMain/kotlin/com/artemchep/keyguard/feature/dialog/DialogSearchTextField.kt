package com.artemchep.keyguard.feature.dialog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.home.vault.component.SearchTextField
import com.artemchep.keyguard.ui.focus.FocusRequester2
import kotlinx.coroutines.flow.first

/**
 * A search field for the content of a [Dialog]; the same
 * search pill the list screens show in their toolbars.
 */
@Composable
fun DialogSearchTextField(
    modifier: Modifier = Modifier,
    value: TextFieldModel,
    placeholder: String,
) {
    val focusRequester = remember {
        FocusRequester2()
    }
    // Auto focus the text field on launch, once
    // it becomes editable.
    val updatedValue by rememberUpdatedState(value)
    LaunchedEffect(focusRequester) {
        snapshotFlow { updatedValue.onChange != null }
            .first { it }
        focusRequester.requestFocus()
    }
    SearchTextField(
        modifier = modifier,
        text = value.text,
        textRevision = value.textRevision,
        placeholder = placeholder,
        focusRequester = focusRequester,
        focusFlow = value.focusFlow,
        leading = {},
        trailing = {},
        onTextChange = value.onChange,
    )
}
