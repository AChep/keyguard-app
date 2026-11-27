package com.artemchep.keyguard.feature.masterkdf

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.warning
import org.jetbrains.compose.resources.stringResource

/**
 * The key derivation (Argon2id) form: the fields, a test of
 * the unlock time and a reset to the defaults.
 */
@Composable
fun MasterKdfForm(
    state: MasterKdfFormState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = stringResource(Res.string.masterkdf_note),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
        Spacer(Modifier.height(8.dp))
        MasterKdfFieldGroup(
            state = state,
        )
        ExpandedIfNotEmpty(
            valueOrNull = state.warning,
        ) { warning ->
            MasterKdfWarning(
                modifier = Modifier
                    .padding(top = 8.dp),
                text = warning,
            )
        }
        Spacer(Modifier.height(8.dp))
        MasterKdfActions(
            state = state,
        )
        ExpandedIfNotEmpty(
            valueOrNull = state.test.resultText,
        ) { text ->
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                text = text,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MasterKdfFieldGroup(
    state: MasterKdfFormState,
) {
    MasterKdfNumberField(
        testTag = "field:kdf.iterations",
        label = stringResource(Res.string.masterkdf_iterations_label),
        value = state.iterations,
        shapeState = ShapeState.START,
        imeAction = ImeAction.Next,
    )
    Spacer(Modifier.height(3.dp))
    MasterKdfNumberField(
        testTag = "field:kdf.memory",
        label = stringResource(Res.string.masterkdf_memory_label),
        value = state.memory,
        shapeState = ShapeState.CENTER,
        imeAction = ImeAction.Next,
    )
    Spacer(Modifier.height(3.dp))
    MasterKdfNumberField(
        testTag = "field:kdf.parallelism",
        label = stringResource(Res.string.masterkdf_parallelism_label),
        value = state.parallelism,
        shapeState = ShapeState.END,
        imeAction = ImeAction.Done,
    )
}

@Composable
private fun MasterKdfNumberField(
    testTag: String,
    label: String,
    value: TextFieldModel,
    shapeState: Int,
    imeAction: ImeAction,
) {
    FlatTextField(
        modifier = Modifier
            .fillMaxWidth(),
        testTag = testTag,
        label = label,
        value = value,
        shapeState = shapeState,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Number,
            imeAction = imeAction,
        ),
        singleLine = true,
        maxLines = 1,
        clearButton = false, // Minimize used space
    )
}

@Composable
private fun MasterKdfWarning(
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth(),
    ) {
        Icon(
            modifier = Modifier
                .size(18.dp),
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.warning,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            modifier = Modifier
                .weight(1f),
            text = text,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MasterKdfActions(
    state: MasterKdfFormState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val onTest by rememberUpdatedState(state.test.onTest)
        FilledTonalButton(
            shapes = ButtonDefaults.shapes(),
            enabled = state.test.onTest != null,
            onClick = {
                onTest?.invoke()
            },
        ) {
            Crossfade(
                modifier = Modifier
                    .size(ButtonDefaults.IconSize),
                targetState = state.test.isRunning,
            ) { isRunning ->
                if (isRunning) {
                    KeyguardLoadingIndicator()
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = null,
                    )
                }
            }
            Spacer(
                modifier = Modifier
                    .width(ButtonDefaults.IconSpacing),
            )
            Text(
                text = stringResource(Res.string.masterkdf_test_button),
            )
        }
        Spacer(
            modifier = Modifier
                .weight(1f),
        )
        val onReset by rememberUpdatedState(state.onReset)
        TextButton(
            shapes = ButtonDefaults.shapes(),
            enabled = state.onReset != null,
            onClick = {
                onReset?.invoke()
            },
        ) {
            Text(
                text = stringResource(Res.string.reset),
            )
        }
    }
}
