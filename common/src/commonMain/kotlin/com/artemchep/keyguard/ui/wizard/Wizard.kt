package com.artemchep.keyguard.ui.wizard

import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.KeyguardLoadingIndicator
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.scaffoldContentWindowInsets
import com.artemchep.keyguard.ui.screenMaxWidthCompact
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

/**
 * A screen that shows one step of a multi-step flow at a time.
 * The [content] of a step starts with a [WizardHeader]; a `null`
 * [step] shows a loading indicator.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : Any> WizardScaffold(
    title: String,
    step: T?,
    navigationIcon: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
    val listState = remember(step) { LazyListState() }
    ScaffoldLazyColumn(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        listState = listState,
        topBar = {
            LargeToolbar(
                title = { Text(title) },
                navigationIcon = navigationIcon,
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
    ) {
        item("wizard") {
            WizardColumn {
                if (step == null) {
                    KeyguardLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Crossfade(
                        targetState = step,
                        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "wizard_step",
                    ) { currentStep ->
                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.verticalPadding)) {
                            content(currentStep)
                        }
                    }
                }
            }
        }
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
 * while [onClick] is `null`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WizardBottomBar(
    text: String,
    onClick: (() -> Unit)?,
    loading: Boolean = false,
) {
    val focusManager = LocalFocusManager.current
    Surface {
        Box(
            modifier = Modifier.fillMaxWidth().windowInsetsPadding(
                scaffoldContentWindowInsets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
        ) {
            WizardColumn {
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.fieldHorizontalPadding),
                    shapes = ButtonDefaults.shapes(),
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
 * The step label, progress, title and detail of a step.
 */
@Composable
fun WizardHeader(
    stepNumber: Int,
    stepCount: Int,
    title: String,
    detail: String,
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
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
