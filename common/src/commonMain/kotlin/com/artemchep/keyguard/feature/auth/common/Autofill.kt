package com.artemchep.keyguard.feature.auth.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.autofill(
    value: String,
    autofillTypes: List<AutofillType>,
    onFill: ((String) -> Unit)?,
) = composed {
    val autofillNode = remember(autofillTypes, onFill) {
        AutofillNode(
            onFill = onFill,
            autofillTypes = autofillTypes,
        )
    }
    val updatedAutofill by rememberUpdatedState(newValue = LocalAutofill.current)
    val updatedAutofillNode by rememberUpdatedState(newValue = autofillNode)

    // TODO: Do we need to remove the node at some point? Do
    //  we need to add it every recomposition?
    val autofillTree = LocalAutofillTree.current
    autofillTree += autofillNode

    AutofillSideEffect(
        value = value,
        node = autofillNode,
    )

    this
        .onGloballyPositioned {
            autofillNode.boundingBox = it.boundsInWindow()
        }
        .onFocusChanged { focusState ->
            val autofill = updatedAutofill
                ?: return@onFocusChanged
            if (focusState.isFocused) {
                // To avoid exception:
                // requestAutofill called before onChildPositioned()
                //
                // we first check if the element has been positioned already.
                // Note that a few lines of code we update the position.
                if (autofillNode.boundingBox != null) {
                    autofill.requestAutofillForNode(updatedAutofillNode)
                }
            } else {
                autofill.cancelAutofillForNode(updatedAutofillNode)
            }
        }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
expect fun AutofillSideEffect(
    value: String,
    node: AutofillNode,
)

