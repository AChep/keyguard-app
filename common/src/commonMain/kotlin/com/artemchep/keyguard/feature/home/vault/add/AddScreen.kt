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
import com.artemchep.keyguard.feature.add.AddScreenItems
import com.artemchep.keyguard.feature.add.AddScreenScope
import com.artemchep.keyguard.feature.add.ToolbarContent
import com.artemchep.keyguard.feature.add.ToolbarContentItemErrSkeleton
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
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.LeMOdelBottomSheet
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OptionsButton
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.PasswordPwnedBadge
import com.artemchep.keyguard.ui.PasswordStrengthBadge
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.button.FavouriteToggleButton
import com.artemchep.keyguard.ui.focus.focusRequester2
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
    with(addScreenScope) {
        AddScreenItems()
    }
}

@Composable
private fun ColumnScope.populateItemsContent(
    addScreenScope: AddScreenScope,
    state: AddState,
) {
    ToolbarContent(
        modifier = Modifier,
        account = state.ownership.ui.account,
        organization = state.ownership.ui.organization,
        collection = state.ownership.ui.collection,
        folder = state.ownership.ui.folder,
        onClick = state.ownership.ui.onClick,
    )
    Section()
    if (state.merge != null) {
        ExpandedIfNotEmpty(
            valueOrNull = state.merge.note,
        ) { note ->
            FlatSimpleNote(
                modifier = Modifier,
                note = note,
            )
        }
        FlatItemLayout(
            leading = {
                Checkbox(
                    checked = state.merge.removeOrigin.checked,
                    onCheckedChange = null,
                )
            },
            content = {
                Text(
                    text = stringResource(Res.strings.additem_merge_remove_origin_ciphers_title),
                )
            },
            onClick = {
                val newValue = !state.merge.removeOrigin.checked
                state.merge.removeOrigin.onChange?.invoke(newValue)
            },
        )
        Section()
    }
    Spacer(Modifier.height(24.dp))
    with(addScreenScope) {
        AddScreenItems(
            items = state.items,
        )
    }
}
