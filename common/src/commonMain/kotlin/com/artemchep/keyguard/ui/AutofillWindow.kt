package com.artemchep.keyguard.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.GroupableShapeItem
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getShapeState
import com.artemchep.keyguard.feature.generator.AutoResizeText
import com.artemchep.keyguard.feature.generator.DecoratedSlider
import com.artemchep.keyguard.feature.generator.FilterItem
import com.artemchep.keyguard.feature.generator.GENERATOR_KEY_ARG_URIS
import com.artemchep.keyguard.feature.generator.GeneratorRoute
import com.artemchep.keyguard.feature.generator.GeneratorState
import com.artemchep.keyguard.feature.generator.GeneratorType
import com.artemchep.keyguard.feature.generator.colorizePasswordOrEmpty
import com.artemchep.keyguard.feature.generator.produceGeneratorState
import com.artemchep.keyguard.feature.home.vault.component.FlatDropdownSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemTextContent2
import com.artemchep.keyguard.feature.home.vault.component.HorizontalContextItems
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.feature.navigation.state.LaunchParameterEffect
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.animation.animateContentHeight
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.StateFlow

@Composable
fun AutofillButton(
    key: String,
    username: Boolean = false,
    password: Boolean = false,
    sshKey: Boolean = false,
    provideUris: () -> ImmutableList<String> = { persistentListOf() },
    onValueChange: ((String) -> Unit)? = null,
    onResultChange: ((GetPasswordResult) -> Unit)? = null,
) {
    var isAutofillWindowShowing by remember {
        mutableStateOf(false)
    }

    val enabled = onValueChange != null || onResultChange != null
    if (!enabled) {
        // We can not autofill disabled text fields and we can not
        // tease user with it.
        isAutofillWindowShowing = false
    }

    IconButton(
        enabled = enabled,
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
            val uris = remember(provideUris) {
                provideUris()
            }
            AutofillWindow(
                key = key,
                username = username,
                password = password,
                sshKey = sshKey,
                uris = uris,
                onComplete = { result ->
                    if (onValueChange != null && result != null && result is GetPasswordResult.Value) {
                        onValueChange(result.value)
                    }
                    if (onResultChange != null && result != null) {
                        onResultChange(result)
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
    sshKey: Boolean = false,
    uris: List<String> = emptyList(),
    onComplete: (GetPasswordResult?) -> Unit,
) {
    val args = remember(username, password, sshKey, uris) {
        GeneratorRoute.Args(
            context = GeneratorRoute.Args.Context(
                uris = uris,
            ),
            username = username,
            password = password,
            sshKey = sshKey,
        )
    }
    val loadableState = produceGeneratorState(
        mode = LocalAppMode.current,
        args = args,
        key = key,
    )
    LaunchParameterEffect(
        key = "$key:autofill",
        parameter = GENERATOR_KEY_ARG_URIS,
        value = uris,
    )

    loadableState.fold(
        ifLoading = {
            SkeletonItem()
        },
        ifOk = { state ->
            val text = when {
                username && password -> stringResource(Res.string.generator_header_title)
                username -> stringResource(Res.string.generator_header_username_title)
                password -> stringResource(Res.string.generator_header_password_title)
                sshKey -> stringResource(Res.string.generator_header_ssh_key_title)
                else -> stringResource(Res.string.generator_header_title)
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
    onComplete: (GetPasswordResult?) -> Unit,
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
    onComplete: (GetPasswordResult?) -> Unit,
) {
    val filter by state.filterState.collectAsState()
    val filterLengthSliderInteractionSource = remember {
        MutableInteractionSource()
    }

    GeneratorType(
        state = state,
    )

    GeneratorValue2(
        loadedFlow = state.loadedState,
        valueFlow = state.valueState,
        sliderInteractionSource = filterLengthSliderInteractionSource,
        onComplete = onComplete,
    )

    GeneratorSuggestions(
        suggestionsFlow = state.suggestionsState,
        onComplete = onComplete,
    )

    val showSection = filter.length != null || filter.items.isNotEmpty()
    ExpandedIfNotEmpty(
        Unit.takeIf { showSection },
    ) {
        Section(
            text = stringResource(Res.string.filter_header_title),
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

    val items = filter.items
    items.forEachIndexed { index, item ->
        key(item.key) {
            val shapeState = getShapeState(
                list = items,
                index = index,
                predicate = { el, _ -> el is GroupableShapeItem<*> },
            )
            FilterItem(item, shapeState = shapeState)
        }
    }
}

@Composable
private fun ColumnScope.GeneratorValue2(
    loadedFlow: StateFlow<GeneratorState.Loading?>,
    valueFlow: StateFlow<GeneratorState.Value?>,
    sliderInteractionSource: MutableInteractionSource,
    onComplete: (GetPasswordResult?) -> Unit,
) {
    val loadedState = loadedFlow.collectAsState()
    val valueState = valueFlow.collectAsState()
    ExpandedIfNotEmpty(
        valueOrNull = valueState.value,
    ) { value ->
        val updatedOnRefresh by rememberUpdatedState(value.onRefresh)
        val actions by remember(valueState) {
            derivedStateOf {
                val loaded = loadedState.value?.loaded == true
                val leading: @Composable () -> Unit = if (loaded) {
                    icon(Icons.Outlined.Refresh)
                } else {
                    // composable
                    {
                        KeyguardLoadingIndicator()
                    }
                }
                val onClick: (() -> Unit)? = if (loaded) {
                    // lambda
                    {
                        updatedOnRefresh?.invoke()
                    }
                } else {
                    null
                }

                val valueExists = !valueState.value?.password.isNullOrEmpty()
                if (valueExists) {
                    persistentListOf(
                        FlatItemAction(
                            leading = leading,
                            title = Res.string.generator_regenerate_button.wrap(),
                            onClick = onClick,
                        ),
                    )
                } else {
                    persistentListOf(
                        FlatItemAction(
                            leading = leading,
                            title = Res.string.generator_generate_button.wrap(),
                            onClick = onClick,
                        ),
                    )
                }
            }
        }

        Column {
            val sliderInteractionSourceIsInteracted =
                sliderInteractionSource.collectIsInteractedWith()
            FlatDropdownSimpleExpressive(
                content = {
                    ExpandedIfNotEmpty(
                        valueOrNull = value.title,
                    ) { title ->
                        Text(
                            modifier = Modifier
                                .padding(bottom = 4.dp),
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Crossfade(
                        targetState = value.password,
                        modifier = Modifier
                            .animateContentHeight(),
                    ) { password ->
                        AutoResizeText(
                            text = if (password.isEmpty()) {
                                val color = LocalContentColor.current
                                    .combineAlpha(DisabledEmphasisAlpha)
                                val text = stringResource(Res.string.empty_value)
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
                    val updatedSource by rememberUpdatedState(value.source)
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
                            updatedOnClick.invoke(updatedSource)
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                            )
                        },
                        text = {
                            Text(
                                text = stringResource(Res.string.generator_use_button),
                            )
                        },
                    )
                },
                dropdown = value.dropdown,
                enabled = !sliderInteractionSourceIsInteracted,
            )
            HorizontalContextItems(
                modifier = Modifier
                    .padding(top = 4.dp),
                items = actions,
                colors = ButtonDefaults.outlinedButtonColors(),
                elevation = null,
                border = ButtonDefaults.outlinedButtonBorder(),
            )
        }
    }
}

@Composable
fun ColumnScope.GeneratorSuggestions(
    suggestionsFlow: StateFlow<ImmutableList<GeneratorState.Suggestion>>,
    onComplete: (GetPasswordResult?) -> Unit,
) {
    val updatedOnComplete by rememberUpdatedState(onComplete)
    val valueState = suggestionsFlow.collectAsState()
    ExpandedIfNotEmpty(
        valueOrNull = valueState.value
            .takeIf { items ->
                items.isNotEmpty()
            },
    ) { value ->
        Column(
            modifier = Modifier,
        ) {
            Section(
                text = stringResource(Res.string.context_based_suggestions),
                expressive = true,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Dimens.contentPadding),
                horizontalArrangement = Arrangement.spacedBy(Dimens.contentPadding)
            ) {
                value.forEach { i ->
                    FlatItemLayoutExpressive(
                        modifier = Modifier
                            .widthIn(max = 220.dp),
                        padding = PaddingValues(0.dp),
                        content = {
                            FlatItemTextContent2(
                                title = if (i.context != null) {
                                    // composable
                                    {
                                        Text(
                                            text = i.context,
                                        )
                                    }
                                } else {
                                    null
                                },
                                text = {
                                    Text(
                                        text = colorizePasswordOrEmpty(i.value),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                        },
                        onClick = {
                            val result = GetPasswordResult.Value(i.value)
                            updatedOnComplete(result)
                        },
                    )
                }
            }
        }
    }
}
