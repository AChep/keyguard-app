package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.justgetdata.AhDifficulty
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.tfa.directory.FlatLaunchBrowserItem
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.FlatItem
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.ScaffoldColumn
import com.artemchep.keyguard.ui.icons.ChevronIcon
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardTwoFa
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.markdown.MarkdownText
import com.artemchep.keyguard.ui.poweredby.PoweredByJustGetMyData
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun JustGetMyDataViewDialogScreen(
    args: JustGetMyDataViewDialogRoute.Args,
) {
    val loadableState = produceJustGetMyDataViewState(
        args = args,
    )
    JustGetMyDataViewDialogScreen(
        args = args,
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JustGetMyDataViewDialogScreen(
    args: JustGetMyDataViewDialogRoute.Args,
    loadableState: Loadable<JustGetMyDataViewState>,
) {
    Dialog(
        icon = icon(Icons.Outlined.KeyguardTwoFa),
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
                        ?.model?.name
                        .orEmpty()
                    Text(
                        text = title,
                    )
                }
            }
            Text(
                text = stringResource(Res.string.justgetmydata_title),
                style = MaterialTheme.typography.titleSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
            AhDifficulty(
                modifier = Modifier
                    .padding(top = 4.dp),
                model = args.model,
            )
        },
        content = {
            Column {
                Content(
                    args = args,
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
fun JustGetMyDataViewFullScreen(
    args: JustGetMyDataViewDialogRoute.Args,
) {
    val loadableState = produceJustGetMyDataViewState(
        args = args,
    )
    JustGetMyDataViewFullScreen(
        args = args,
        loadableState = loadableState,
    )
}

@Composable
fun JustGetMyDataViewFullScreen(
    args: JustGetMyDataViewDialogRoute.Args,
    loadableState: Loadable<JustGetMyDataViewState>,
) {
    val scrollBehavior = ToolbarBehavior.behavior()
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
                                ?.model?.name
                                .orEmpty()
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
        AhDifficulty(
            modifier = Modifier
                .padding(horizontal = 16.dp),
            model = args.model,
        )
        Spacer(
            modifier = Modifier
                .height(8.dp),
        )
        Content(
            args = args,
        )
    }
}

@Composable
fun ColumnScope.Content(
    args: JustGetMyDataViewDialogRoute.Args,
) {
    val notes = args.model.notes
    if (notes != null) {
        MarkdownText(
            modifier = Modifier
                .padding(horizontal = Dimens.horizontalPadding),
            markdown = notes,
        )
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }

    val navigationController by rememberUpdatedState(LocalNavigationController.current)

    val updatedEmail by rememberUpdatedState(args.model.email)
    if (updatedEmail != null) {
        FlatItem(
            elevation = 1.dp,
            leading = {
                IconBox(Icons.Outlined.Email)
            },
            title = {
                Text(
                    text = stringResource(Res.string.justdeleteme_send_email_title),
                )
            },
            trailing = {
                ChevronIcon()
            },
            onClick = {
                // Open the email, if it's available.
                updatedEmail?.let {
                    val intent = NavigationIntent.NavigateToEmail(
                        it,
                        subject = args.model.emailSubject,
                        body = args.model.emailBody,
                    )
                    navigationController.queue(intent)
                }
            },
            enabled = updatedEmail != null,
        )
    }

    val websiteUrl = args.model.url
    if (websiteUrl != null) {
        FlatLaunchBrowserItem(
            title = stringResource(Res.string.uri_action_launch_website_title),
            url = websiteUrl,
        )
    }

    Spacer(
        modifier = Modifier
            .height(8.dp),
    )

    PoweredByJustGetMyData(
        modifier = Modifier
            .padding(horizontal = Dimens.horizontalPadding)
            .fillMaxWidth(),
    )
}
