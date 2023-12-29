package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillNode

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun AutofillSideEffect(
    value: String,
    node: AutofillNode,
) {
    // Do nothing.
}