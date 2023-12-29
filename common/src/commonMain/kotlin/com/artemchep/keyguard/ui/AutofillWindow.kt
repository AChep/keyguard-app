package com.artemchep.keyguard.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.generator.AutoResizeText
import com.artemchep.keyguard.feature.generator.DecoratedSlider
import com.artemchep.keyguard.feature.generator.FilterItem
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.feature.generator.GeneratorType
import com.artemchep.keyguard.feature.generator.produceGeneratorState
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.combineAlpha
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AutofillButton(
    key: String,
    username: Boolean = false,
    password: Boolean = false,
    onValueChange: ((String) -> Unit)? = null,
) {
    var isAutofillWindowShowing by remember {
        mutableStateOf(false)
    }

    val enabled = onValueChange != null
    if (!enabled) {
        // We can not autofill disabled text fields and we can not
        // tease user with it.
        isAutofillWindowShowing = false
    }

    IconButton(
        enabled = onValueChange != null,
        onClick = {
            isAutofillWindowShowing = !isAutofillWindowShowing
        },
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = null,
        )
    }
    // Inject the dropdown popup to the bottom of the
    // content.
    val onDismissRequest = {
        isAutofillWindowShowing = false
    }
    LeMOdelBottomSheet(
        visible = isAutofillWindowShowing,
        onDismissRequest = onDismissRequest,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            AutofillWindow(
                key = key,
                username = username,
                password = password,
                onComplete = { password ->
                    if (onValueChange != null && password != null) {
                        onValueChange(password)
                    }
                    // Hide the dropdown window on click on one
                    // of the buttons.
                    onDismissRequest()
                },
            )
        }
    }
}

@Composable
expect fun LeMOdelBottomSheet(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
)

@Composable
fun ColumnScope.AutofillWindow(
    key: String,
    username: Boolean = false,
    password: Boolean = false,
    onComplete: (String?) -> Unit,
) {
    val args = remember(username, password) {
        GeneratorRoute.Args(
            username = username,
            password = password,
        )
    }
    val loadableState = produceGeneratorState(
        mode = LocalAppMode.current,
        args = args,
        key = key,
    )
    loadableState.fold(
        ifLoading = {
            SkeletonItem()
        },
        ifOk = { state ->
            val text = when {
                username && password -> stringResource(Res.strings.generator_header_title)
                username -> stringResource(Res.strings.generator_header_username_title)
                password -> stringResource(Res.strings.generator_header_password_title)
                else -> stringResource(Res.strings.generator_header_title)
            }
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                text = text,
                style = MaterialTheme.typography.titleLarge,
            )
            AutofillWindow(
                state = state,
                onComplete = onComplete,
            )
        },
    )
}

@Composable
fun ColumnScope.AutofillWindow(
    state: GeneratorState,
    onComplete: (String?) -> Unit,
) {
    AutofillWindowContent(
        state = state,
        onComplete = onComplete,
    )
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
fun ColumnScope.AutofillWindowContent(
    state: GeneratorState,
    onComplete: (String?) -> Unit,
) {
    val filter by state.filterState.collectAsState()
    val filterLengthSliderInteractionSource = remember {
        MutableInteractionSource()
    }

    GeneratorType(
        state = state,
    )

    GeneratorValue2(
        valueFlow = state.valueState,
        sliderInteractionSource = filterLengthSliderInteractionSource,
        onComplete = onComplete,
    )

    val showSection = filter.length != null || filter.items.isNotEmpty()
    ExpandedIfNotEmpty(
        Unit.takeIf { showSection },
    ) {
        Section(
            text = stringResource(Res.strings.filter_header_title),
        )
    }

    ExpandedIfNotEmpty(
        filter.length,
    ) {
        Column {
            DecoratedSlider(
                filterLength = it,
                interactionSource = filterLengthSliderInteractionSource,
            )
        }
    }

    Spacer(
        modifier = Modifier
            .height(16.dp),
    )

    filter.items.forEachIndexed { index, item ->
        key(item.key) {
            FilterItem(item)
        }
    }
}

@Composable
private fun ColumnScope.GeneratorValue2(
    valueFlow: StateFlow<GeneratorState.Value?>,
    sliderInteractionSource: MutableInteractionSource,
    onComplete: (String?) -> Unit,
) {
    val valueState = valueFlow.collectAsState()
    ExpandedIfNotEmpty(
        valueOrNull = valueState.value,
    ) { value ->
        val updatedOnRefresh by rememberUpdatedState(value.onRefresh)

        val generateTitle = stringResource(Res.strings.generator_generate_button)
        val regenerateTitle = stringResource(Res.strings.generator_regenerate_button)
        val actions by remember(valueState) {
            derivedStateOf {
                val valueExists = !valueState.value?.password.isNullOrEmpty()
                if (valueExists) {
                    listOf(
                        FlatItemAction(
                            leading = icon(Icons.Outlined.Refresh),
                            title = regenerateTitle,
                            onClick = {
                                updatedOnRefresh?.invoke()
                            },
                        ),
                    )
                } else {
                    listOf(
                        FlatItemAction(
                            leading = icon(Icons.Outlined.Refresh),
                            title = generateTitle,
                            onClick = {
                                updatedOnRefresh?.invoke()
                            },
                        ),
                    )
                }
            }
        }

        val sliderInteractionSourceIsInteracted =
            sliderInteractionSource.collectIsInteractedWith()
        FlatDropdown(
            elevation = 8.dp,
            content = {
                Crossfade(
                    targetState = value.password,
                    modifier = Modifier
                        .animateContentSize(),
                ) { password ->
                    AutoResizeText(
                        text = if (password.isEmpty()) {
                            val color = LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha)
                            val text = stringResource(Res.strings.empty_value)
                            buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(color = color),
                                ) {
                                    append(text)
                                }
                            }
                        } else {
                            colorizePassword(
                                password = password,
                                contentColor = LocalContentColor.current,
                            )
                        },
                    )
                }
                Spacer(
                    modifier = Modifier
                        .height(4.dp),
                )

                val visible = value.strength
                ExpandedIfNotEmpty(
                    valueOrNull = value.password.takeIf { visible },
                ) { password ->
                    PasswordStrengthBadge(
                        password = password,
                    )
                }
            },
            trailing = {
                val updatedPassword by rememberUpdatedState(value.password)
                val updatedOnClick by rememberUpdatedState(onComplete)
                ExtendedFloatingActionButton(
                    modifier = Modifier
                        .then(
                            if (updatedPassword.isNotEmpty()) {
                                Modifier
                            } else {
                                Modifier.alpha(DisabledEmphasisAlpha)
                            },
                        ),
                    onClick = {
                        if (updatedPassword.isNotEmpty()) {
                            updatedOnClick.invoke(updatedPassword)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text("Use")
                    },
                )
            },
            dropdown = value.dropdown,
            actions = actions,
            enabled = !sliderInteractionSourceIsInteracted,
        )
    }
}
