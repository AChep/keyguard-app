@file:OptIn(ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.home.vault.add

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import arrow.core.partially1
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.UsernameVariationIcon
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.filepicker.FilePickerEffect
import com.artemchep.keyguard.feature.home.vault.component.FlatItemTextContent2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.VaultViewTotpBadge2
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.qr.ScanQrButton
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.BiFlatContainer
import com.artemchep.keyguard.ui.BiFlatTextField
import com.artemchep.keyguard.ui.BiFlatTextFieldLabel
import com.artemchep.keyguard.ui.BiFlatValueHeightMin
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.EmailFlatTextField
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatDropdown
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.LeMOdelBottomSheet
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.PasswordPwnedBadge
import com.artemchep.keyguard.ui.PasswordStrengthBadge
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.ui.icons.KeyguardOrganization
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.shimmer.shimmer
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.skeleton.SkeletonTextField
import com.artemchep.keyguard.ui.text.annotatedResource
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.isDark
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.util.DividerColor
import com.artemchep.keyguard.ui.util.HorizontalDivider
import com.artemchep.keyguard.ui.util.VerticalDivider
import dev.icerock.moko.resources.compose.stringResource
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.kodein.di.compose.rememberInstance

@Composable
fun AddScreen(
    args: AddRoute.Args,
) {
    val loadableState = produceAddScreenState(
        args = args,
    )

    val state = loadableState.getOrNull()
    if (state != null) {
        FilePickerEffect(
            flow = state.sideEffects.filePickerIntentFlow,
        )
    }

    val addScreenScope = remember {
        val addScreenBehavior = args.behavior
        AddScreenScope(
            initialFocusRequested = !addScreenBehavior.autoShowKeyboard,
        )
    }
    AddScreenContent(
        addScreenScope = addScreenScope,
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalLayoutApi::class)
@Composable
private fun AddScreenContent(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<AddState>,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    val title = loadableState.getOrNull()?.title
                    if (title != null) {
                        Text(title)
                    } else {
                        SkeletonText(
                            modifier = Modifier
                                .fillMaxWidth(0.4f),
                        )
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                actions = {
                    val fav = loadableState.getOrNull()?.favourite

                    val checked = fav?.checked == true
                    val onChange = fav?.onChange
                    FavouriteToggleButton(
                        modifier = Modifier
                            .then(
                                loadableState.fold(
                                    ifLoading = {
                                        Modifier
                                            .shimmer()
                                    },
                                    ifOk = {
                                        Modifier
                                    },
                                ),
                            ),
                        favorite = checked,
                        onChange = onChange,
                    )

                    val actions = loadableState.getOrNull()?.actions.orEmpty()
                    OptionsButton(
                        actions = actions,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionState = run {
            val fabOnClick = loadableState.getOrNull()?.onSave
            val fabState = if (fabOnClick != null) {
                FabState(
                    onClick = fabOnClick,
                    model = null,
                )
            } else {
                null
            }
            rememberUpdatedState(newValue = fabState)
        },
        floatingActionButton = {
            DefaultFab(
                icon = {
                    Icon(
                        imageVector = Icons.Outlined.Save,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        text = stringResource(Res.strings.save),
                    )
                },
            )
        },
        columnVerticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        populateItems(
            addScreenScope = addScreenScope,
            loadableState = loadableState,
        )
    }
}

private class AddScreenScope(
    initialFocusRequested: Boolean = false,
) {
    val initialFocusRequestedState = mutableStateOf(initialFocusRequested)

    @Composable
    fun initialFocusRequesterEffect(): FocusRequester {
        val focusRequester = remember { FocusRequester() }
        // Auto focus the text field
        // on launch.
        LaunchedEffect(focusRequester) {
            var initialFocusRequested by initialFocusRequestedState
            if (!initialFocusRequested) {
                focusRequester.requestFocus()
                // do not request it the second time
                initialFocusRequested = true
            }
        }
        return focusRequester
    }
}

@Composable
private fun ColumnScope.populateItems(
    addScreenScope: AddScreenScope,
    loadableState: Loadable<AddState>,
) = loadableState.fold(
    ifLoading = {
        populateItemsSkeleton(
            addScreenScope = addScreenScope,
        )
    },
    ifOk = { state ->
        populateItemsContent(
            addScreenScope = addScreenScope,
            state = state,
        )
    },
)

@Composable
private fun ColumnScope.populateItemsSkeleton(
    addScreenScope: AddScreenScope,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarContentItemErrSkeleton(
                modifier = Modifier
                    .weight(1.5f),
                fraction = 0.5f,
            )
            Spacer(modifier = Modifier.width(8.dp))
            ToolbarContentItemErrSkeleton(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                fraction = 0.75f,
            )
        }
        ToolbarContentItemErrSkeleton(
            modifier = Modifier
                .padding(end = 8.dp),
            fraction = 0.35f,
        )
    }
    Section()
    Spacer(Modifier.height(24.dp))
    SkeletonTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
    )
    SkeletonTextField(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.horizontalPadding),
    )
    SkeletonText(
        modifier = Modifier
            .fillMaxWidth(0.75f)
            .padding(horizontal = Dimens.horizontalPadding),
        style = MaterialTheme.typography.labelMedium,
    )
    SkeletonText(
        modifier = Modifier
            .fillMaxWidth(0.4f)
            .padding(horizontal = Dimens.horizontalPadding),
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun ColumnScope.populateItemsContent(
    addScreenScope: AddScreenScope,
    state: AddState,
) {
    ToolbarContent(
        modifier = Modifier,
        account = state.ownership.account,
        organization = state.ownership.organization,
        collection = state.ownership.collection,
        folder = state.ownership.folder,
        onClick = state.ownership.onClick,
    )
    Section()
    Spacer(Modifier.height(24.dp))
    val logRepository by rememberInstance<LogRepository>()
    remember(state) {
        logRepository.post("Foo3", "rendered state ${state.items.size} items")
    }
    state.items.forEach {
        key(it.id) {
            AnyField(
                modifier = Modifier,
                addScreenScope = addScreenScope,
                item = it,
            )
        }
    }
}

@Composable
private fun AnyField(
    modifier: Modifier = Modifier,
    addScreenScope: AddScreenScope,
    item: AddStateItem,
) {
    when (item) {
        is AddStateItem.Title -> {
            val localState by item.state.flow.collectAsState()
            NameTextField(
                modifier = modifier,
                addScreenScope = addScreenScope,
                field = localState,
            )
        }

        is AddStateItem.Username -> {
            val localState by item.state.flow.collectAsState()
            UsernameTextField(modifier, localState)
        }

        is AddStateItem.Password -> {
            val localState by item.state.flow.collectAsState()
            PasswordTextField(modifier, localState)
        }

        is AddStateItem.Totp -> {
            val localState by item.state.flow.collectAsState()
            TotpTextField(modifier, localState)
        }

        is AddStateItem.Url -> {
            UrlTextField(modifier, item)
        }

        is AddStateItem.Attachment -> {
            AttachmentTextField(modifier, item)
        }

        is AddStateItem.Passkey -> {
            PasskeyField(modifier, item)
        }

        is AddStateItem.Note -> {
            val localState by item.state.flow.collectAsState()
            NoteTextField(modifier, localState, markdown = item.markdown)
        }

        is AddStateItem.Text -> {
            val localState by item.state.flow.collectAsState()
            TextTextField(modifier, localState)
        }

        is AddStateItem.DateMonthYear -> {
            DateMonthYearField(
                modifier = modifier,
                item = item,
            )
        }

        is AddStateItem.Switch -> {
            SwitchField(
                modifier = modifier,
                item = item,
            )
        }

        is AddStateItem.Suggestion -> {
            SuggestionItem(
                modifier = modifier,
                item = item,
            )
        }

        is AddStateItem.Enum -> {
            val localState by item.state.flow.collectAsState()
            EnumItem(
                modifier = modifier,
                icon = item.icon,
                label = item.label,
                title = localState.value,
                dropdown = localState.dropdown,
            )
        }

        is AddStateItem.Field -> {
            Box(
                modifier = modifier,
            ) {
                val logRepository by rememberInstance<LogRepository>()
                remember(item) {
                    logRepository.post("Foo3", "im rendering a field! ${item.id}")
                }
                val localState = remember {
                    mutableStateOf<AddStateItem.Field.State?>(null)
                }
                LaunchedEffect(item.state.flow) {
                    item.state.flow
                        .map { a ->
                            a.withOptions(a.options + item.options)
                        }
                        .onEach {
                            localState.value = it
                            logRepository.post("Foo3", "im on emit a field! ${it}")
                        }
                        .launchIn(this)
                }
//                val localState = remember(item.state.flow) {
//                    item.state.flow
//                        .map { a ->
//                            a.withOptions(a.options + item.options)
//                        }
//                        .onEach {
//                            logRepository.post("Foo3", "im on emit a field! ${it}")
//                        }
//                }.collectAsState(null)
                when (val l = localState.value) {
                    is AddStateItem.Field.State.Text -> {
                        FieldTextField(Modifier, l)
                    }

                    is AddStateItem.Field.State.Switch -> {
                        FieldSwitchField(Modifier, l)
                    }

                    is AddStateItem.Field.State.LinkedId -> {
                        FieldLinkedIdField(Modifier, l)
                    }

                    null -> {
                        // Do nothing.
                    }
                }
            }
        }

        is AddStateItem.Section -> {
            SectionItem(modifier, item.text)
        }
        // Modifiers
        is AddStateItem.Add -> {
            Column(
                modifier = modifier,
            ) {
                val dropdownShownState = remember { mutableStateOf(false) }
                if (item.actions.isEmpty()) {
                    dropdownShownState.value = false
                }

                // Inject the dropdown popup to the bottom of the
                // content.
                val onDismissRequest = remember(dropdownShownState) {
                    // lambda
                    {
                        dropdownShownState.value = false
                    }
                }
                val contentColor = MaterialTheme.colorScheme.primary
                FlatItem(
                    leading = {
                        Icon(Icons.Outlined.Add, null, tint = contentColor)
                    },
                    title = {
                        Text(
                            text = item.text,
                            color = contentColor,
                        )

                        DropdownMenu(
                            modifier = Modifier
                                .widthIn(min = DropdownMinWidth),
                            expanded = dropdownShownState.value,
                            onDismissRequest = onDismissRequest,
                        ) {
                            val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                            with(scope) {
                                item.actions.forEachIndexed { index, action ->
                                    DropdownMenuItemFlat(
                                        action = action,
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        if (item.actions.size == 1) {
                            item.actions.first()
                                .onClick?.invoke()
                        } else {
                            dropdownShownState.value = true
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun NameTextField(
    modifier: Modifier = Modifier,
    addScreenScope: AddScreenScope,
    field: TextFieldModel2,
) {
    val focusManager = LocalFocusManager.current
    val keyboardOnNext: KeyboardActionScope.() -> Unit = {
        focusManager.moveFocus(FocusDirection.Down)
    }
    val focusRequester = addScreenScope.initialFocusRequesterEffect()
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        fieldModifier = Modifier
            .focusRequester(focusRequester),
        label = stringResource(Res.strings.generic_name),
        singleLine = true,
        value = field,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(
            onNext = keyboardOnNext,
        ),
    )
}

@Composable
private fun UsernameTextField(
    modifier: Modifier = Modifier,
    field: AddStateItem.Username.State,
) {
    EmailFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.strings.username),
        value = field.value,
        leading = {
            Crossfade(
                targetState = field.type,
            ) { variation ->
                UsernameVariationIcon(
                    usernameVariation = variation,
                )
            }
        },
        trailing = {
            AutofillButton(
                key = "username",
                username = true,
                onValueChange = {
                    field.value.onChange?.invoke(it)
                },
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordTextField(
    modifier: Modifier = Modifier,
    field: TextFieldModel2,
) = Column {
    PasswordFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = field,
        leading = {
            IconBox(
                main = Icons.Outlined.Password,
            )
        },
        trailing = {
            AutofillButton(
                key = "password",
                password = true,
                onValueChange = {
                    field.onChange?.invoke(it)
                },
            )
        },
        content = {
            ExpandedIfNotEmpty(
                valueOrNull = Unit.takeIf { field.state.value.isNotEmpty() && field.error == null },
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 8.dp,
                            bottom = 8.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PasswordStrengthBadge(
                        password = field.state.value,
                    )
                    PasswordPwnedBadge(
                        password = field.state.value,
                    )
                }
            }
        },
    )
    Text(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .padding(
                top = Dimens.topPaddingCaption,
                bottom = Dimens.topPaddingCaption,
            ),
        style = MaterialTheme.typography.bodyMedium,
        text = stringResource(Res.strings.generator_password_note, 16),
        color = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
    )
}

@Composable
private fun TotpTextField(
    modifier: Modifier = Modifier,
    state: AddStateItem.Totp.State,
) {
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.strings.one_time_password_authenticator_key),
        value = state.value,
        textStyle = LocalTextStyle.current.copy(
            fontFamily = monoFontFamily,
        ),
        singleLine = true,
        maxLines = 1,
        trailing = {
            ScanQrButton(
                onValueChange = state.value.onChange,
            )
        },
        leading = {
            IconBox(
                main = Icons.Outlined.KeyguardTwoFa,
            )
        },
    )
    ExpandedIfNotEmpty(
        modifier = Modifier
            .fillMaxWidth(),
        valueOrNull = state.totpToken,
    ) { totpToken ->
        Box {
            VaultViewTotpBadge2(
                modifier = Modifier
                    .padding(start = 16.dp),
                copyText = state.copyText,
                totpToken = totpToken,
            )
        }
    }
}

@Composable
private fun NoteTextField(
    modifier: Modifier = Modifier,
    field: TextFieldModel2,
    markdown: Boolean,
) = Column(
    modifier = modifier,
) {
    var isAutofillWindowShowing by remember {
        mutableStateOf(false)
    }
    if (!markdown) {
        isAutofillWindowShowing = false
    }

    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.strings.note),
        value = field,
        content = if (markdown) {
            // composable
            {
                ExpandedIfNotEmpty(
                    Unit.takeIf { field.text.isNotEmpty() },
                ) {
                    TextButton(
                        onClick = {
                            isAutofillWindowShowing = true
                        },
                    ) {
                        Text(
                            text = stringResource(Res.strings.additem_markdown_render_preview),
                        )
                    }
                }
            }
        } else {
            null
        },
        // TODO: Long note moves a lot when you focus then field, because
        //  the clear button suddenly appears. We might move the button
        //  to the footer or something to keep the functionality.
        clearButton = false,
    )
    if (markdown) {
        val description = annotatedResource(
            Res.strings.additem_markdown_note,
            stringResource(Res.strings.additem_markdown_note_italic)
                .let { "*$it*" } to SpanStyle(
                fontStyle = FontStyle.Italic,
            ),
            stringResource(Res.strings.additem_markdown_note_bold)
                .let { "**$it**" } to SpanStyle(
                fontWeight = FontWeight.Bold,
            ),
        )
        Row(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .padding(top = Dimens.topPaddingCaption),
        ) {
            val navigationController = LocalNavigationController.current
            Text(
                modifier = Modifier
                    .weight(1f),
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.combineAlpha(alpha = MediumEmphasisAlpha),
            )
            Spacer(
                modifier = Modifier
                    .width(16.dp),
            )
            TextButton(
                onClick = {
                    val intent = NavigationIntent.NavigateToBrowser(
                        url = "https://www.markdownguide.org/basic-syntax/",
                    )
                    navigationController.queue(intent)
                },
            ) {
                Text(
                    text = stringResource(Res.strings.learn_more),
                )
            }
        }
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
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                text = "Markdown",
                style = MaterialTheme.typography.titleLarge,
            )
            MarkdownText(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.horizontalPadding,
                        vertical = Dimens.verticalPadding,
                    ),
                markdown = field.text,
            )
        }
    }
}

@Composable
private fun UrlTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Url,
) {
    val state by item.state.flow.collectAsState()
    UrlFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.strings.uri),
        value = state.text,
        leading = {
            IconBox(
                main = Icons.Outlined.KeyguardWebsite,
            )
        },
        trailing = {
            val actions = remember(
                state.options,
                item.options,
            ) {
                state.options + item.options
            }
            OptionsButton(
                actions = actions,
            )
        },
        content = {
            ExpandedIfNotEmpty(
                valueOrNull = state.matchTypeTitle,
            ) { matchType ->
                FlatTextFieldBadge(
                    type = TextFieldModel2.Vl.Type.INFO,
                    text = matchType,
                )
            }
        },
    )
}

@Composable
private fun AttachmentTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Attachment,
) {
    val state by item.state.flow.collectAsState()
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = "File",
        placeholder = "File name",
        value = state.name,
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Text,
        ),
        singleLine = true,
        maxLines = 1,
        leading = {
            Box {
                Icon(
                    imageVector = Icons.Outlined.KeyguardAttachment,
                    contentDescription = null,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.shapes.extraSmall,
                        ),
                ) {
                    if (state.synced) {
                        Icon(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(1.dp),
                            imageVector = Icons.Outlined.CloudDone,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            contentDescription = null,
                        )
                    }
                    if (!state.synced) {
                        Icon(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(1.dp),
                            imageVector = Icons.Outlined.FileUpload,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
        content = {
            ExpandedIfNotEmpty(
                valueOrNull = state.size,
            ) { fileSize ->
                Column {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = fileSize,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        trailing = {
            OptionsButton(
                actions = item.options,
            )
        },
    )
}

@Composable
private fun PasskeyField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Passkey,
) {
    val state by item.state.flow.collectAsState()
    FlatItemLayout(
        modifier = modifier,
        leading = icon<RowScope>(Icons.Outlined.Key),
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = 8.dp,
        ),
        content = {
            val passkey = state.passkey
            if (passkey != null) {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = passkey.userDisplayName.orEmpty(),
                        )
                    },
                    text = {
                        Text(
                            text = passkey.rpName,
                        )
                    },
                )
            } else {
                FlatItemTextContent(
                    title = {
                        Text(
                            text = stringResource(Res.strings.passkey),
                        )
                    },
                )
            }
        },
        trailing = {
            OptionsButton(
                actions = item.options,
            )
        },
        enabled = true,
    )
}

@Composable
private fun TextTextField(
    modifier: Modifier = Modifier,
    state: AddStateItem.Text.State,
) {
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = state.label,
        value = state.value,
        singleLine = state.singleLine,
        keyboardOptions = state.keyboardOptions,
        visualTransformation = state.visualTransformation,
    )
}

@Composable
private fun FieldTextField(
    modifier: Modifier = Modifier,
    field: AddStateItem.Field.State.Text,
) {
    val visibilityState = remember(field.hidden) {
        VisibilityState(
            isVisible = !field.hidden,
        )
    }
    BiFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = field.label,
        value = field.text,
        valueVisualTransformation = if (visibilityState.isVisible || !field.hidden) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                valueOrNull = Unit.takeIf { field.hidden },
            ) {
                VisibilityToggle(
                    visibilityState = visibilityState,
                )
            }
            OptionsButton(
                actions = field.options,
            )
        },
    )
}

@Composable
private fun FieldSwitchField(
    modifier: Modifier = Modifier,
    field: AddStateItem.Field.State.Switch,
) {
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = field.label,
        trailing = {
            Checkbox(
                checked = field.checked,
                onCheckedChange = field.onCheckedChange,
            )
            OptionsButton(
                actions = field.options,
            )
        },
    )
}

@Composable
private fun FieldLinkedIdField(
    modifier: Modifier = Modifier,
    field: AddStateItem.Field.State.LinkedId,
) {
    val label = field.label

    val labelInteractionSource = remember { MutableInteractionSource() }
    val valueInteractionSource = remember { MutableInteractionSource() }

    val isError = remember(
        label.error,
    ) {
        derivedStateOf {
            label.error != null
        }
    }

    val hasFocusState = remember {
        mutableStateOf(false)
    }

    val isEmpty = remember(
        label.state,
    ) {
        derivedStateOf {
            label.state.value.isBlank()
        }
    }

    val dropdownShownState = remember { mutableStateOf(false) }
    if (field.actions.isEmpty()) {
        dropdownShownState.value = false
    }

    // Inject the dropdown popup to the bottom of the
    // content.
    val onDismissRequest = remember(dropdownShownState) {
        // lambda
        {
            dropdownShownState.value = false
        }
    }

    BiFlatContainer(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .onFocusChanged { state ->
                hasFocusState.value = state.hasFocus
            },
        contentModifier = Modifier
            .clickable(
                indication = LocalIndication.current,
                interactionSource = valueInteractionSource,
                role = Role.Button,
            ) {
                dropdownShownState.value = true
            },
        isError = isError,
        isFocused = hasFocusState,
        isEmpty = isEmpty,
        label = {
            BiFlatTextFieldLabel(
                label = label,
                interactionSource = labelInteractionSource,
            )
        },
        content = {
            Row {
                val textColor =
                    if (field.value != null) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha)
                    }
                Text(
                    modifier = Modifier
                        .heightIn(min = BiFlatValueHeightMin)
                        .weight(1f, fill = false),
                    text = (field.value?.titleH() ?: Res.strings.select_linked_type)
                        .let { stringResource(it) },
                    color = textColor,
                )
                Spacer(
                    modifier = Modifier
                        .width(4.dp),
                )
                Icon(
                    modifier = Modifier
                        .alpha(MediumEmphasisAlpha),
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                )
            }

            DropdownMenu(
                modifier = Modifier
                    .widthIn(min = DropdownMinWidth),
                expanded = dropdownShownState.value,
                onDismissRequest = onDismissRequest,
            ) {
                val scope = DropdownScopeImpl(this, onDismissRequest = onDismissRequest)
                with(scope) {
                    field.actions.forEachIndexed { index, action ->
                        DropdownMenuItemFlat(
                            action = action,
                        )
                    }
                }
            }
        },
        trailing = {
            OptionsButton(
                actions = field.options,
            )
        },
    )
}

@Composable
private fun SwitchField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Switch,
) {
    val localState by item.state.flow.collectAsState()
    FlatItem(
        modifier = modifier,
        leading = {
            Checkbox(
                checked = localState.checked,
                enabled = localState.onChange != null,
                onCheckedChange = null,
            )
        },
        title = {
            Text(
                text = item.title,
            )
        },
        text = if (item.text != null) {
            // composable
            {
                Text(
                    text = item.text,
                )
            }
        } else {
            null
        },
        onClick = localState.onChange?.partially1(!localState.checked),
    )
}

@Composable
private fun DateMonthYearField(
    modifier: Modifier = Modifier,
    item: AddStateItem.DateMonthYear,
) {
    val localState by item.state.flow.collectAsState()
    BiFlatContainer(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        contentModifier = Modifier
            .clickable(
                indication = LocalIndication.current,
                interactionSource = remember {
                    MutableInteractionSource()
                },
                role = Role.Button,
            ) {
                localState.onClick.invoke()
            },
        isError = rememberUpdatedState(newValue = false),
        isFocused = rememberUpdatedState(newValue = false),
        isEmpty = rememberUpdatedState(newValue = false),
        label = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        content = {
            val density = LocalDensity.current
            Row(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = 12f + density.density
                    },
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = BiFlatValueHeightMin)
                        .weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val month = localState.month.text.ifBlank { "--" }
                    val year = localState.year.text.ifBlank { "----" }
                    Text(
                        text = month,
                    )
                    Text(
                        text = "/",
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                    Text(
                        text = year,
                    )
                }
//                Spacer(
//                    modifier = Modifier
//                        .width(4.dp),
//                )
//                Icon(
//                    modifier = Modifier
//                        .alpha(MediumEmphasisAlpha),
//                    imageVector = Icons.Outlined.ArrowDropDown,
//                    contentDescription = null,
//                )
            }
        },
        trailing = {
            val isEmpty = localState.month.state.value.isEmpty() &&
                    localState.year.state.value.isEmpty()
            ExpandedIfNotEmptyForRow(
                valueOrNull = Unit.takeUnless { isEmpty },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    VerticalDivider(
                        modifier = Modifier
                            .height(24.dp),
                    )
                    Spacer(
                        modifier = Modifier
                            .width(8.dp),
                    )
                    IconButton(
                        enabled = true,
                        onClick = {
                            localState.month.state.value = ""
                            localState.year.state.value = ""
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Clear,
                            contentDescription = null,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun EnumItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    title: String,
    dropdown: List<FlatItemAction>,
) {
    FlatDropdown(
        modifier = modifier,
        content = {
            FlatItemTextContent2(
                title = {
                    Text(label)
                },
                text = {
                    Text(title)
                },
            )
        },
        leading = {
            Icon(icon, null)
        },
        dropdown = dropdown,
    )
}

@Composable
private fun SectionItem(
    modifier: Modifier = Modifier,
    title: String?,
) {
    Section(
        modifier = modifier,
        text = title,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SuggestionItem(
    modifier: Modifier = Modifier,
    item: AddStateItem.Suggestion,
) {
    val localState by item.state.flow.collectAsState()
    FlowRow(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        localState.items.forEach { item ->
            key(item.key) {
                SuggestionItemChip(
                    modifier = Modifier,
                    item = item,
                )
            }
        }
    }
}

@Composable
private fun SuggestionItemChip(
    modifier: Modifier = Modifier,
    item: AddStateItem.Suggestion.Item,
) {
    val backgroundColor =
        if (item.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        modifier = modifier,
        border = if (item.selected) null else BorderStroke(0.dp, DividerColor),
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
    ) {
        val contentColor = LocalContentColor.current
            .let { color ->
                if (item.onClick != null) {
                    color // enabled
                } else {
                    color.combineAlpha(DisabledEmphasisAlpha)
                }
            }
        CompositionLocalProvider(
            LocalContentColor provides contentColor,
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (item.onClick != null) {
                            Modifier
                                .clickable(role = Role.Button) {
                                    item.onClick.invoke()
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
            ) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        }
    }
}

//
// Ownership
//

@Composable
private fun ToolbarContentItem(
    element: AddState.SaveToElement,
) {
    element.items.forEach {
        key(it.key) {
            Column(
                modifier = Modifier
                    .heightIn(min = 32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = it.title,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (it.text != null) {
                    Text(
                        text = it.text,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarContentItemErr(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    element: AddState.SaveToElement,
) {
    ToolbarContentItemErr(
        modifier = modifier,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        element = element,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarContentItemErr(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    element: AddState.SaveToElement,
) {
    val alpha = if (!element.readOnly) DefaultEmphasisAlpha else DisabledEmphasisAlpha
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .alpha(alpha),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp),
            ) {
                leading()
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarContentItem(element = element)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolbarContentItemErrSkeleton(
    modifier: Modifier = Modifier,
    fraction: Float = 1f,
) {
    Row(
        modifier = modifier
            .heightIn(min = 32.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            val contentColor = LocalContentColor.current
                .combineAlpha(DisabledEmphasisAlpha)
            Box(
                modifier = Modifier
                    .shimmer()
                    .size(16.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(contentColor),
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(min = 32.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                SkeletonText(
                    modifier = Modifier
                        .fillMaxWidth(fraction),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ToolbarContent(
    modifier: Modifier = Modifier,
    account: AddState.SaveToElement? = null,
    organization: AddState.SaveToElement? = null,
    collection: AddState.SaveToElement? = null,
    folder: AddState.SaveToElement? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 8.dp,
                vertical = 2.dp,
            )
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                },
            )
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (account != null) {
                ToolbarContentItemErr(
                    modifier = Modifier
                        .weight(1.5f),
                    leading = {
                        val accentColors = account.items.firstOrNull()
                            ?.accentColors
                            ?: return@ToolbarContentItemErr
                        val backgroundColor = if (MaterialTheme.colorScheme.isDark) {
                            accentColors.dark
                        } else {
                            accentColors.light
                        }
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .background(backgroundColor, CircleShape),
                        )
                    },
                    element = account,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (folder != null) {
                ToolbarContentItemErr(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    icon = Icons.Outlined.Folder,
                    element = folder,
                )
            }
        }
        ExpandedIfNotEmpty(
            valueOrNull = organization,
        ) { org ->
            ToolbarContentItemErr(
                modifier = Modifier
                    .padding(end = 8.dp),
                icon = Icons.Outlined.KeyguardOrganization,
                element = org,
            )
        }
        ExpandedIfNotEmpty(
            valueOrNull = collection,
        ) { col ->
            ToolbarContentItemErr(
                modifier = Modifier
                    .padding(end = 8.dp),
                icon = Icons.Outlined.KeyguardCollection,
                element = col,
            )
        }
    }
}
