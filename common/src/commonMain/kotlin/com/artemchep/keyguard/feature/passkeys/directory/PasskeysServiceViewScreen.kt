package com.artemchep.keyguard.feature.passkeys.directory

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.tfa.directory.FlatLaunchBrowserItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.poweredby.PoweredByPasskeys
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasskeysViewDialogScreen(
    args: PasskeysServiceViewDialogRoute.Args,
) {
    val loadableState = producePasskeysServiceViewState(
        args = args,
    )
    PasskeysViewDialogScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PasskeysViewDialogScreen(
    loadableState: Loadable<PasskeysServiceViewState>,
) {
    Dialog(
        icon = icon(Icons.Outlined.Key),
        title = {
            when (loadableState) {
                is Loadable.Loading -> {
                    SkeletonText(
                        modifier = Modifier
                            .width(72.dp),
                    )
                }

                is Loadable.Ok -> {
                    val title = loadableState.value.content.getOrNull()
                        ?.model?.name.orEmpty()
                    Text(
                        text = title,
                    )
                }
            }

            Text(
                text = stringResource(Res.string.passkeys_directory_title),
                style = MaterialTheme.typography.titleSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )

            HeaderChips(
                modifier = Modifier
                    .padding(top = 4.dp),
                loadableState = loadableState,
            )
        },
        content = {
            Column {
                Content(
                    loadableState = loadableState,
                )
            }
        },
        contentScrollable = true,
        actions = {
            val updatedOnClose by rememberUpdatedState(loadableState.getOrNull()?.onClose)
            TextButton(
                enabled = updatedOnClose != null,
                onClick = {
                    updatedOnClose?.invoke()
                },
            ) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
fun PasskeysViewFullScreen(
    args: PasskeysServiceViewDialogRoute.Args,
) {
    val loadableState = producePasskeysServiceViewState(
        args = args,
    )
    PasskeysViewFullScreen(
        loadableState = loadableState,
    )
}

@Composable
fun PasskeysViewFullScreen(
    loadableState: Loadable<PasskeysServiceViewState>,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    ScaffoldColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    when (loadableState) {
                        is Loadable.Loading -> {
                            SkeletonText(
                                modifier = Modifier
                                    .width(72.dp),
                            )
                        }

                        is Loadable.Ok -> {
                            val title = loadableState.value.content.getOrNull()
                                ?.model?.name.orEmpty()
                            Text(
                                text = title,
                            )
                        }
                    }
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        HeaderChips(
            modifier = Modifier
                .padding(
                    horizontal = Dimens.horizontalPadding,
                ),
            loadableState = loadableState,
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Content(
            loadableState = loadableState,
        )
    }
}

@Composable
fun ColumnScope.Content(
    loadableState: Loadable<PasskeysServiceViewState>,
) {
    val state = loadableState.getOrNull()?.content?.getOrNull()
    val notes = state?.model?.notes
    if (notes != null) {
        MarkdownText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            markdown = notes,
        )
    }

    val category = state?.model?.category
    if (category != null) {
        Section(
            text = stringResource(Res.string.category),
        )
        Text(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            text = category,
        )
    }

    val documentationUrl = state?.model?.documentation
    if (documentationUrl != null) {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        FlatLaunchBrowserItem(
            title = stringResource(Res.string.uri_action_launch_docs_title),
            url = documentationUrl,
        )
    }

    Spacer(
        modifier = Modifier
            .height(8.dp),
    )

    PoweredByPasskeys(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderChips(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    loadableState: Loadable<PasskeysServiceViewState>,
) {
    HeaderChipContainer(
        modifier = modifier,
        horizontalAlignment = horizontalAlignment,
    ) {
        val chipModifier = Modifier
            .background(
                color = LocalContentColor.current
                    .combineAlpha(DisabledEmphasisAlpha)
                    .combineAlpha(DisabledEmphasisAlpha),
                shape = MaterialTheme.shapes.small,
            )
            .padding(
                horizontal = 4.dp,
                vertical = 2.dp,
            )
        val chipTextStyle = MaterialTheme.typography.labelSmall
        when (loadableState) {
            is Loadable.Loading -> {
                repeat(2) {
                    SkeletonText(
                        modifier = chipModifier
                            .width(48.dp),
                        style = chipTextStyle,
                    )
                }
            }

            is Loadable.Ok -> {
                val types = loadableState.value.content.getOrNull()
                    ?.model?.features
                types?.forEach { type ->
                    Text(
                        modifier = chipModifier,
                        text = type,
                        style = chipTextStyle,
                        fontFamily = monoFontFamily,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderChipContainer(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, horizontalAlignment),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}
