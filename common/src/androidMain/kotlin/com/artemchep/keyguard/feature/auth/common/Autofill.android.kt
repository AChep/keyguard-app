package com.artemchep.keyguard.feature.auth.common

import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.getSystemService

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun AutofillSideEffect(
    value: String,
    node: AutofillNode,
) {
    val view = LocalView.current
    val autofillManager = LocalContext.current.getSystemService<AutofillManager>()
    SideEffect {
        val autofillId = node.id
        autofillManager?.notifyValueChanged(view, autofillId, AutofillValue.forText(value))
    }
}
