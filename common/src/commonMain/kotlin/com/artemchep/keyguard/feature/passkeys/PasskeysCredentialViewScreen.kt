package com.artemchep.keyguard.feature.passkeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.feature.dialog.Dialog
import com.artemchep.keyguard.feature.home.vault.component.FlatItemTextContent2
import com.artemchep.keyguard.feature.home.vault.component.Section
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.FlatItemLayout
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.icons.KeyguardView
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.skeleton.SkeletonSection
import com.artemchep.keyguard.ui.skeleton.SkeletonText
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource

@Composable
fun PasskeysCredentialViewScreen(
    args: PasskeysCredentialViewRoute.Args,
) {
    val loadableState = producePasskeysCredentialViewState(
        args = args,
        mode = LocalAppMode.current,
    )
    PasskeysCredentialViewScreen(
        loadableState = loadableState,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PasskeysCredentialViewScreen(
    loadableState: Loadable<PasskeysCredentialViewState>,
) {
    Dialog(
        icon = icon(Icons.Outlined.Key),
        title = {
            Text(
                text = stringResource(Res.string.passkey),
            )
        },
        content = {
            Column {
                when (loadableState) {
                    is Loadable.Ok -> {
                        loadableState.value.content.fold(
                            ifLeft = {
                                ContentError(
                                    exception = it,
                                )
                            },
                            ifRight = {
                                ContentOk(
                                    state = it,
                                )
                            },
                        )
                    }

                    is Loadable.Loading -> {
                        ContentSkeleton()
                    }
                }
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
            val onUse = loadableState.getOrNull()?.content?.getOrNull()?.onUse
            if (onUse != null) {
                val updatedOnUse by rememberUpdatedState(onUse)
                TextButton(
                    onClick = {
                        updatedOnUse.invoke()
                    },
                ) {
                    Text(stringResource(Res.string.passkey_use_short))
                }
            }
        },
    )
}

@Composable
private fun ColumnScope.ContentOk(
    state: PasskeysCredentialViewState.Content,
) {
    state.passkeysCredentialViewItems().forEach { item ->
        PasskeysCredentialItem(item = item)
    }
}

@Composable
private fun PasskeysCredentialItem(
    item: PasskeysCredentialViewItem,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    when (item) {
        is PasskeysCredentialViewItem.Text -> {
            PasskeysCredentialTextItem(
                icon = item.icon,
                title = stringResource(item.title),
                value = item.value,
            )
        }

        is PasskeysCredentialViewItem.Section -> {
            Section(
                text = stringResource(item.title),
            )
        }

        is PasskeysCredentialViewItem.RelyingParty -> {
            FlatItemLayout(
                leading = {
                    IconBox(item.icon)
                },
                content = {
                    FlatItemTextContent2(
                        title = {
                            Text(
                                text = stringResource(Res.string.passkey_relying_party),
                            )
                        },
                        text = {
                            Column {
                                Text(
                                    text = item.rpName
                                        ?: stringResource(Res.string.empty_value),
                                    color = if (item.rpName != null) {
                                        LocalContentColor.current
                                    } else {
                                        LocalContentColor.current
                                            .combineAlpha(DisabledEmphasisAlpha)
                                    },
                                )
                                Text(
                                    text = item.rpId,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        },
                    )
                },
                trailing = {
                    PasskeysCredentialInfoButton(
                        url = item.infoUrlOrNull(),
                        onOpen = { url ->
                            navigationController.queue(NavigationIntent.NavigateToBrowser(url))
                        },
                    )
                },
                enabled = true,
            )
        }

        is PasskeysCredentialViewItem.SignatureCounter -> {
            PasskeysCredentialTextItem(
                icon = item.icon,
                title = stringResource(Res.string.passkey_signature_counter),
                value = item.counter?.toString(),
                infoUrl = item.infoUrlOrNull(),
            )
        }

        is PasskeysCredentialViewItem.Discoverable -> {
            PasskeysCredentialTextItem(
                icon = item.icon,
                title = stringResource(Res.string.passkey_discoverable),
                value = if (item.value) {
                    stringResource(Res.string.yes)
                } else {
                    stringResource(Res.string.no)
                },
                showEmptyValue = false,
                infoUrl = item.infoUrlOrNull(),
            )
        }

        is PasskeysCredentialViewItem.CreatedAt -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Dimens.horizontalPadding,
                        end = Dimens.horizontalPadding,
                        top = 16.dp,
                    ),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.text,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha),
                )
            }
        }
    }
}

@Composable
private fun PasskeysCredentialTextItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String?,
    showEmptyValue: Boolean = true,
    infoUrl: String? = null,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    FlatItemLayout(
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = title,
                    )
                },
                text = {
                    Text(
                        text = value
                            ?: stringResource(Res.string.empty_value),
                        color = if (value != null || !showEmptyValue) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha)
                        },
                    )
                },
            )
        },
        trailing = if (infoUrl != null) {
            {
                PasskeysCredentialInfoButton(
                    url = infoUrl,
                    onOpen = { url ->
                        navigationController.queue(NavigationIntent.NavigateToBrowser(url))
                    },
                )
            }
        } else {
            null
        },
        enabled = true,
    )
}

@Composable
private fun PasskeysCredentialInfoButton(
    url: String?,
    onOpen: (String) -> Unit,
) {
    IconButton(
        enabled = url != null,
        onClick = {
            url?.let(onOpen)
        },
    ) {
        Icon(Icons.Outlined.Info, null)
    }
}

@Composable
private fun ColumnScope.ContentError(
    exception: Throwable,
) {
    val message = exception.message
        ?: stringResource(Res.string.error_failed_unknown)
    Text(
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .align(Alignment.CenterHorizontally),
        text = message,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun ColumnScope.ContentSkeleton(
) {
    SkeletonItem(
        titleWidthFraction = 0.6f,
        textWidthFraction = null,
    )
    SkeletonSection()
    SkeletonItem(
        titleWidthFraction = 0.45f,
        textWidthFraction = 0.3f,
    )
    SkeletonItem(
        titleWidthFraction = 0.5f,
        textWidthFraction = 0.4f,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimens.horizontalPadding,
                end = Dimens.horizontalPadding,
                top = 16.dp,
            ),
        horizontalArrangement = Arrangement.Center,
    ) {
        SkeletonText(
            modifier = Modifier
                .fillMaxWidth(0.8f),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
