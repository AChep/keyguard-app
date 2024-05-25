package com.artemchep.keyguard.feature.add

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActionScope
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.artemchep.keyguard.common.model.UsernameVariationIcon
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.home.vault.component.FlatItemTextContent2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.home.vault.component.VaultViewTotpBadge2
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.qr.ScanQrButton
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.AutofillButton
import com.artemchep.keyguard.ui.BiFlatContainer
import com.artemchep.keyguard.ui.BiFlatTextField
import com.artemchep.keyguard.ui.BiFlatTextFieldLabel
import com.artemchep.keyguard.ui.BiFlatValueHeightMin
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.DefaultEmphasisAlpha
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.DropdownMenuItemFlat
import com.artemchep.keyguard.ui.DropdownMinWidth
import com.artemchep.keyguard.ui.DropdownScopeImpl
import com.artemchep.keyguard.ui.EmailFlatTextField
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FakeFlatTextField
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
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.buildContextItems
import com.artemchep.keyguard.ui.focus.focusRequester2
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardAttachment
import com.artemchep.keyguard.ui.icons.KeyguardCollection
import com.artemchep.keyguard.ui.icons.KeyguardOrganization
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.KeyguardWebsite
import com.artemchep.keyguard.ui.icons.Stub
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
import com.artemchep.keyguard.ui.util.DividerColor
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource
import kotlinx.collections.immutable.ImmutableList

private val paddingValues = PaddingValues(
    horizontal = 8.dp,
)

context(AddScreenScope)
fun LazyListScope.AddScreenItems(
) {
    item("items.skeleton.1") {
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
    item("items.skeleton.2") {
        SkeletonTextField(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.horizontalPadding),
        )
    }
    item("items.skeleton.3") {
        SkeletonText(
            modifier = Modifier
                .fillMaxWidth(0.75f)
                .padding(horizontal = Dimens.horizontalPadding),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    item("items.skeleton.4") {
        SkeletonText(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .padding(horizontal = Dimens.horizontalPadding),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

context(AddScreenScope)
@Composable
fun AnyField(
    modifier: Modifier = Modifier,
    item: AddStateItem,
) = when (item) {
    is AddStateItem.Title<*> -> {
        TitleTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Username<*> -> {
        UsernameTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Password<*> -> {
        PasswordTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Totp<*> -> {
        TotpTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Url<*> -> {
        UrlTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Attachment<*> -> {
        AttachmentTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Passkey<*> -> {
        PasskeyField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Note<*> -> {
        NoteTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Text<*> -> {
        TextTextField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.DateMonthYear<*> -> {
        DateMonthYearField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.DateTime<*> -> {
        DateTimeField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Switch<*> -> {
        SwitchField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Suggestion<*> -> {
        SuggestionItem(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Enum<*> -> {
        EnumItem(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Field<*> -> {
        FieldField(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Section -> {
        SectionItem(
            modifier = modifier,
            item = item,
        )
    }

    is AddStateItem.Add -> {
        AddItem(
            modifier = modifier,
            item = item,
        )
    }
}

context(AddScreenScope)
@Composable
private fun TitleTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Title<*>,
) {
    val field by item.state.flow.collectAsState()

    val keyboardOnNext: KeyboardActionScope.() -> Unit = run {
        val updatedFocusManager by rememberUpdatedState(LocalFocusManager.current)
        remember {
            // lambda
            {
                updatedFocusManager.moveFocus(FocusDirection.Down)
            }
        }
    }
    val focusRequester = initialFocusRequesterEffect()
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        fieldModifier = Modifier
            .focusRequester2(focusRequester),
        label = stringResource(Res.string.generic_name),
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

context(AddScreenScope)
@Composable
private fun UsernameTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Username<*>,
) {
    val state by item.state.flow.collectAsState()
    val field = state.value
    EmailFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.string.username),
        value = field,
        leading = {
            Crossfade(
                targetState = state.type,
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
                onValueChange = field.onChange,
            )
        },
    )
}

context(AddScreenScope)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PasswordTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Password<*>,
) = Column(modifier = modifier) {
    val field by item.state.flow.collectAsState()
    PasswordFlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = field,
        label = item.label,
        leading = {
            IconBox(
                main = Icons.Outlined.Password,
            )
        },
        trailing = {
            AutofillButton(
                key = "password",
                password = true,
                onValueChange = field.onChange,
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
        text = stringResource(Res.string.generator_password_note, 16),
        color = LocalContentColor.current
            .combineAlpha(alpha = MediumEmphasisAlpha),
    )
}

context(AddScreenScope)
@Composable
private fun TotpTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Totp<*>,
) = Column(modifier = modifier) {
    val state by item.state.flow.collectAsState()
    val field = state.value
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.string.one_time_password_authenticator_key),
        value = field,
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
                    .padding(top = 8.dp)
                    .padding(start = 16.dp),
                copyText = state.copyText,
                totpToken = totpToken,
            )
        }
    }
}

context(AddScreenScope)
@Composable
private fun NoteTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Note<*>,
) = Column(
    modifier = modifier,
) {
    val field by item.state.flow.collectAsState()
    val markdown = item.markdown

    var isMarkdownRenderPopupShown by remember {
        mutableStateOf(false)
    }
    if (!markdown) {
        isMarkdownRenderPopupShown = false
    }

    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.string.note),
        value = field,
        content = if (markdown) {
            // composable
            {
                ExpandedIfNotEmpty(
                    Unit.takeIf { field.text.isNotEmpty() },
                ) {
                    TextButton(
                        onClick = {
                            isMarkdownRenderPopupShown = true
                        },
                    ) {
                        Text(
                            text = stringResource(Res.string.additem_markdown_render_preview),
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
            Res.string.additem_markdown_note,
            stringResource(Res.string.additem_markdown_note_italic)
                .let { "*$it*" } to SpanStyle(
                fontStyle = FontStyle.Italic,
            ),
            stringResource(Res.string.additem_markdown_note_bold)
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
                    text = stringResource(Res.string.learn_more),
                )
            }
        }
    }
    // Inject the dropdown popup to the bottom of the
    // content.
    val onHideMarkdownRenderPopupRequest = remember {
        // lambda
        {
            isMarkdownRenderPopupShown = false
        }
    }
    LeMOdelBottomSheet(
        visible = isMarkdownRenderPopupShown,
        onDismissRequest = onHideMarkdownRenderPopupRequest,
    ) { contentPadding ->
        val verticalScrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .verticalScroll(verticalScrollState)
                .padding(contentPadding),
        ) {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.horizontalPadding,
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

context(AddScreenScope)
@Composable
private fun UrlTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Url<*>,
) {
    val state by item.state.flow.collectAsState()
    val field = state.text
    UrlFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = stringResource(Res.string.uri),
        value = field,
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
                buildContextItems(
                    state.options,
                    item.options,
                ) {
                    // Do nothing.
                }
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

context(AddScreenScope)
@Composable
private fun AttachmentTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Attachment<*>,
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

context(AddScreenScope)
@Composable
private fun PasskeyField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Passkey<*>,
) {
    val state by item.state.flow.collectAsState()
    FlatItemLayout(
        modifier = modifier,
        leading = icon<RowScope>(Icons.Outlined.Key),
        paddingValues = paddingValues,
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
                            text = stringResource(Res.string.passkey),
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

context(AddScreenScope)
@Composable
private fun TextTextField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Text<*>,
) = Column(modifier = modifier) {
    val state by item.state.flow.collectAsState()
    val field = state.value
    FlatTextField(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding),
        leading = item.leading,
        label = state.label,
        value = field,
        singleLine = state.singleLine,
        keyboardOptions = state.keyboardOptions,
        visualTransformation = state.visualTransformation,
    )
    if (item.note != null) {
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding)
                .padding(
                    top = Dimens.topPaddingCaption,
                    bottom = Dimens.topPaddingCaption,
                ),
            style = MaterialTheme.typography.bodyMedium,
            text = item.note,
            color = LocalContentColor.current
                .combineAlpha(alpha = MediumEmphasisAlpha),
        )
    }
}

context(AddScreenScope)
@Composable
private fun FieldField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Field<*>,
) = Column(modifier = modifier) {
    val state by item.state.flow.collectAsState()
    val actions = remember(
        state.options,
        item.options,
    ) {
        buildContextItems(
            state.options,
            item.options,
        ) {
            // Do nothing.
        }
    }
    when (val s = state) {
        is AddStateItem.Field.State.Text -> {
            FieldTextField(
                state = s,
                actions = actions,
            )
        }

        is AddStateItem.Field.State.Switch -> {
            FieldSwitchField(
                state = s,
                actions = actions,
            )
        }

        is AddStateItem.Field.State.LinkedId -> {
            FieldLinkedIdField(
                state = s,
                actions = actions,
            )
        }
    }
}

context(AddScreenScope)
@Composable
private fun FieldTextField(
    modifier: Modifier = Modifier,
    state: AddStateItem.Field.State.Text,
    actions: ImmutableList<ContextItem>,
) {
    val visibilityState = remember(state.hidden) {
        VisibilityState(
            isVisible = !state.hidden,
        )
    }
    BiFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = state.label,
        value = state.text,
        valueVisualTransformation = if (visibilityState.isVisible || !state.hidden) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailing = {
            ExpandedIfNotEmptyForRow(
                valueOrNull = Unit.takeIf { state.hidden },
            ) {
                VisibilityToggle(
                    visibilityState = visibilityState,
                )
            }
            OptionsButton(
                actions = actions,
            )
        },
    )
}

context(AddScreenScope)
@Composable
private fun FieldSwitchField(
    modifier: Modifier = Modifier,
    state: AddStateItem.Field.State.Switch,
    actions: ImmutableList<ContextItem>,
) {
    FlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        value = state.label,
        trailing = {
            Checkbox(
                checked = state.checked,
                onCheckedChange = state.onCheckedChange,
            )
            OptionsButton(
                actions = actions,
            )
        },
    )
}

context(AddScreenScope)
@Composable
private fun FieldLinkedIdField(
    modifier: Modifier = Modifier,
    state: AddStateItem.Field.State.LinkedId,
    actions: ImmutableList<ContextItem>,
) {
    val label = state.label

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
    if (state.actions.isEmpty()) {
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
                    if (state.value != null) {
                        LocalContentColor.current
                    } else {
                        LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha)
                    }
                Text(
                    modifier = Modifier
                        .heightIn(min = BiFlatValueHeightMin)
                        .weight(1f, fill = false),
                    text = (state.value?.titleH() ?: Res.string.select_linked_type)
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
                    state.actions.forEachIndexed { index, action ->
                        DropdownMenuItemFlat(
                            action = action,
                        )
                    }
                }
            }
        },
        trailing = {
            OptionsButton(
                actions = actions,
            )
        },
    )
}

context(AddScreenScope)
@Composable
private fun SwitchField(
    modifier: Modifier = Modifier,
    item: AddStateItem.Switch<*>,
) {
    val state by item.state.flow.collectAsState()
    val onClick: () -> Unit = remember(item.state.flow) {
        // lambda
        {
            val field = item.state.flow.value
            field.onChange?.invoke(!field.checked)
        }
    }
    FlatItem(
        modifier = modifier,
        paddingValues = paddingValues,
        leading = {
            Checkbox(
                checked = state.checked,
                enabled = state.onChange != null,
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
        onClick = onClick,
    )
}

context(AddScreenScope)
@Composable
private fun DateMonthYearField(
    modifier: Modifier = Modifier,
    item: AddStateItem.DateMonthYear<*>,
) {
    val state by item.state.flow.collectAsState()
    val isEmpty by derivedStateOf {
        val isEmpty = state.month.state.value.isEmpty() &&
                state.year.state.value.isEmpty()
        isEmpty
    }
    val onClear = remember {
        // lambda
        {
            state.month.state.value = ""
            state.year.state.value = ""
        }
    }
    FakeFlatTextField(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        label = item.label,
        value = {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val month = state.month.text.ifBlank { "--" }
                val year = state.year.text.ifBlank { "----" }
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
        },
        onClick = state.onClick,
        onClear = onClear.takeIf { !isEmpty },
    )
}

context(AddScreenScope)
@Composable
private fun DateTimeField(
    modifier: Modifier = Modifier,
    item: AddStateItem.DateTime<*>,
) = Column (modifier = modifier) {
    val state by item.state.flow.collectAsState()
    Row(
        modifier = Modifier,
    ) {
        FlatItemLayout(
            modifier = Modifier
                .weight(1.5f),
            paddingValues = paddingValues,
            content = {
                FlatItemTextContent2(
                    title = {
                        Text(
                            text = stringResource(Res.string.date),
                        )
                    },
                    text = {
                        val text = state.date
                        Text(text)
                    },
                )
            },
            leading = {
                Icon(
                    imageVector = Icons.Stub,
                    contentDescription = null,
                )
            },
            onClick = state.onSelectDate,
        )
        FlatItemLayout(
            modifier = Modifier
                .weight(1f),
            paddingValues = paddingValues,
            content = {
                FlatItemTextContent2(
                    title = {
                        Text(
                            text = stringResource(Res.string.time),
                        )
                    },
                    text = {
                        val text = state.time
                        Text(text)
                    },
                )
            },
            onClick = state.onSelectTime,
        )
    }
    ExpandedIfNotEmpty(state.badge) { badge ->
        FlatTextFieldBadge(
            modifier = Modifier
                .padding(
                    start = 56.dp,
                    top = 0.dp,
                ),
            type = badge.type,
            text = badge.text,
        )
    }
}

context(AddScreenScope)
@Composable
private fun EnumItem(
    modifier: Modifier = Modifier,
    item: AddStateItem.Enum<*>,
) {
    val state by item.state.flow.collectAsState()
    FlatDropdown(
        modifier = modifier,
        paddingValues = paddingValues,
        leading = item.leading,
        content = {
            FlatItemTextContent2(
                title = {
                    Text(item.label)
                },
                text = {
                    Text(state.value)
                },
            )
        },
        dropdown = state.dropdown,
    )
}

context(AddScreenScope)
@Composable
private fun SectionItem(
    modifier: Modifier = Modifier,
    item: AddStateItem.Section,
) {
    Section(
        modifier = modifier,
        text = item.text,
    )
}

context(AddScreenScope)
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionItem(
    modifier: Modifier = Modifier,
    item: AddStateItem.Suggestion<*>,
) {
    val state by item.state.flow.collectAsState()
    FlowRow(
        modifier = modifier
            .padding(horizontal = Dimens.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.items.forEach { item ->
            key(item.key) {
                SuggestionItemChip(
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

context(AddScreenScope)
@Composable
private fun AddItem(
    modifier: Modifier = Modifier,
    item: AddStateItem.Add,
) = Column(
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
        paddingValues = paddingValues,
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
            if (item.actions.size == 1) run {
                val action = item.actions
                    .firstNotNullOfOrNull { it as? FlatItemAction }
                action?.onClick?.invoke()
                return@FlatItem
            }

            dropdownShownState.value = true
        },
    )
}

//
// Ownership
//

@Composable
private fun ToolbarContentItem(
    element: AddStateOwnership.Element,
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
    element: AddStateOwnership.Element,
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
fun ToolbarContentItemErr(
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    element: AddStateOwnership.Element,
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
fun ToolbarContentItemErrSkeleton(
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
fun ToolbarContent(
    modifier: Modifier = Modifier,
    account: AddStateOwnership.Element? = null,
    organization: AddStateOwnership.Element? = null,
    collection: AddStateOwnership.Element? = null,
    folder: AddStateOwnership.Element? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(paddingValues)
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
