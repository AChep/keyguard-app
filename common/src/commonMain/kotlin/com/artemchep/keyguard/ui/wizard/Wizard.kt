package com.artemchep.keyguard.ui.wizard

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.artemchep.keyguard.feature.dialog.focusOnDialogOpen
import com.artemchep.keyguard.feature.navigation.LocalNavigationNodeFinishing
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FabScope
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.focus.arrowKeyFocusNavigation
import com.artemchep.keyguard.ui.scaffoldContentWindowInsets
import com.artemchep.keyguard.ui.screenMaxWidthCompact
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

/**
 * A screen that shows one step of a multi-step flow at a time.
 * The [content] of a step starts with a [WizardHeader]; a `null`
 * [step] shows a loading indicator. The primary action is either
 * a [bottomBar] or a floating action button.
 *
 * In keyboard input mode a step that opens focuses its selected
 * choice, else its first control, else the [WizardBottomBar] action.
 * The arrow keys move focus, except in a focused text field.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : Any> WizardScaffold(
    title: String,
    step: T?,
    navigationIcon: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    floatingActionState: State<FabState?> = rememberUpdatedState(newValue = null),
    floatingActionButton: @Composable FabScope.() -> Unit = {},
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val listState = remember(step) { LazyListState() }
    val primaryFocus = remember { FocusRequester() }
    // The list lies under the bars, so an arrow key search
    // from a bar passes over it. The bars point at the step.
    val contentFocus = remember { FocusRequester() }
    val hasContent = step != null
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            // For the toolbar and the bottom bar. The step
            // has its own, as the list consumes arrow keys.
            .arrowKeyFocusNavigation(afterChildren = true),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        listState = listState,
        topBar = {
            LargeToolbar(
                modifier = Modifier
                    .focusProperties {
                        if (hasContent) down = contentFocus
                    },
                title = { Text(title) },
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            CompositionLocalProvider(LocalWizardPrimaryFocus provides primaryFocus) {
                Box(
                    modifier = Modifier
                        .focusProperties {
                            if (hasContent) up = contentFocus
                        },
                ) {
                    bottomBar()
                }
            }
        },
        floatingActionState = floatingActionState,
        floatingActionButton = floatingActionButton,
    ) {
        item("wizard") {
            WizardColumn {
                if (step == null) {
                    KeyguardLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    WizardSteps(
                        step = step,
                        contentFocus = contentFocus,
                        primaryFocus = primaryFocus,
                        content = content,
                    )
                }
            }
        }
    }
}

/**
 * The [content] of the current [step], faded in. Each step focuses
 * itself when it opens, see [WizardStepFocusEffect].
 */
@Composable
private fun <T : Any> WizardSteps(
    step: T,
    contentFocus: FocusRequester,
    primaryFocus: FocusRequester,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    Crossfade(
        modifier = Modifier
            // Up enters it at its bottom, Down at its top.
            .focusRequester(contentFocus)
            .focusGroup(),
        targetState = step,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "wizard_step",
    ) { currentStep ->
        val stepFocus = remember { WizardStepFocus(FocusRequester()) }
        CompositionLocalProvider(LocalWizardStepFocus provides stepFocus) {
            Column(
                modifier = Modifier
                    .arrowKeyFocusNavigation(afterChildren = true)
                    .focusRequester(stepFocus.group)
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding),
            ) {
                content(currentStep)
            }
        }
        // The outgoing step stays composed while it fades out.
        WizardStepFocusEffect(
            stepFocus = stepFocus,
            current = currentStep == step,
            primaryFocus = primaryFocus,
        )
    }
}

/**
 * Focuses the step once each time it becomes [current], in keyboard
 * input mode only: its selected choice, else its first control, else
 * the [primaryFocus] action.
 */
@Composable
private fun WizardStepFocusEffect(
    stepFocus: WizardStepFocus,
    current: Boolean,
    primaryFocus: FocusRequester,
) {
    val finishing = LocalNavigationNodeFinishing.current
    val inputModeManager = LocalInputModeManager.current
    // Once per time the step becomes current: content that stops
    // finishing, after a cancelled Back gesture, keeps its focus.
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(current, finishing) {
        if (!current) {
            requested = false
            return@LaunchedEffect
        }
        if (finishing || requested) return@LaunchedEffect
        requested = true
        // A step is placed by the layout that
        // comes after its composition.
        withFrameNanos { }
        if (inputModeManager.inputMode != InputMode.Keyboard) return@LaunchedEffect
        val focused = stepFocus.selectedChoice?.requestFocus() == true ||
            // Enter picks the top-left control. Down would pick a small
            // button at the end of a text field, as its center is nearer.
            stepFocus.group.requestFocus(FocusDirection.Enter)
        if (!focused) primaryFocus.requestFocus()
    }
}

/**
 * Goes to the previous step, or leaves the
 * screen when [onBack] is `null`.
 */
@Composable
fun WizardBackButton(
    onBack: (() -> Unit)?,
    enabled: Boolean = true,
) {
    val focusManager = LocalFocusManager.current
    if (onBack == null) {
        NavigationIcon()
    } else {
        IconButton(
            enabled = enabled,
            onClick = {
                focusManager.clearFocus()
                onBack()
            },
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = stringResource(Res.string.additem_key_generator_back_title),
            )
        }
    }
}

@Composable
fun WizardColumn(content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = screenMaxWidthCompact).fillMaxWidth()
                .padding(vertical = Dimens.verticalPadding),
            content = content,
        )
    }
}

/**
 * The primary action of a step, disabled
 * while [onClick] is `null`, with optional
 * [guidance] above it.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WizardBottomBar(
    text: String,
    onClick: (() -> Unit)?,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    guidance: @Composable ColumnScope.() -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val primaryFocus = LocalWizardPrimaryFocus.current
    Surface {
        Box(
            modifier = modifier.fillMaxWidth().windowInsetsPadding(
                scaffoldContentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
        ) {
            WizardColumn {
                guidance()
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.fieldHorizontalPadding)
                        .then(primaryFocus?.let { Modifier.focusRequester(it) } ?: Modifier),
                    shapes = ButtonDefaults.shapes(),
                    colors = if (tonal) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                    enabled = onClick != null,
                    onClick = {
                        focusManager.clearFocus()
                        onClick?.invoke()
                    },
                ) {
                    if (loading) {
                        KeyguardLoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    }
                    Text(text)
                }
            }
        }
    }
}

/**
 * Asks to confirm leaving a wizard
 * with unsaved choices.
 */
@Composable
fun WizardDiscardDialog(
    title: String,
    text: String,
    action: String,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onDiscard) { Text(action) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.focusOnDialogOpen(),
                onClick = onDismiss,
            ) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

/**
 * The step label, progress, title and detail of a step.
 */
@Composable
fun WizardHeader(
    stepNumber: Int,
    stepCount: Int,
    title: String,
    detail: String?,
) {
    Column(
        modifier = Modifier.padding(horizontal = Dimens.textHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding),
    ) {
        Text(
            // The label is shared by every wizard, the key
            // keeps its name to keep the translations.
            text = stringResource(
                Res.string.pref_item_automatic_backups_wizard_step_label,
                stepNumber,
                stepCount,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
            progress = { stepNumber.toFloat() / stepCount },
        )
        Text(
            modifier = Modifier.semantics { heading() },
            text = title,
            style = MaterialTheme.typography.headlineMedium,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
