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
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    FlatItemLayout(
        leading = {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = stringResource(Res.string.passkey_user_display_name),
                    )
                },
                text = {
                    val userDisplayName = state.model.userDisplayName
                    Text(
                        text = userDisplayName
                            ?: stringResource(Res.string.empty_value),
                        color = if (userDisplayName != null) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha)
                        },
                    )
                },
            )
        },
        enabled = true,
    )
    FlatItemLayout(
        leading = {
            Icon(
                imageVector = Icons.Outlined.AlternateEmail,
                contentDescription = null,
            )
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = stringResource(Res.string.passkey_user_username),
                    )
                },
                text = {
                    val userName = state.model.userName
                    Text(
                        text = userName
                            ?: stringResource(Res.string.empty_value),
                        color = if (userName != null) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha)
                        },
                    )
                },
            )
        },
        enabled = true,
    )
    Section(
        text = stringResource(Res.string.info),
    )
    FlatItemLayout(
        leading = {
            IconBox(Icons.Outlined.Business)
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = stringResource(Res.string.passkey_relying_party),
                    )
                },
                text = {
                    val rpName = state.model.rpName
                    val rpId = state.model.rpId
                    Column {
                        Text(
                            text = rpName,
                        )
                        Text(
                            text = rpId,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
            )
        },
        trailing = {
            IconButton(
                onClick = {
                    val url = "https://www.w3.org/TR/webauthn-2/#relying-party"
                    val intent = NavigationIntent.NavigateToBrowser(url)
                    navigationController.queue(intent)
                },
            ) {
                Icon(Icons.Outlined.Info, null)
            }
        },
        enabled = true,
    )
    FlatItemLayout(
        leading = {
            IconBox(Icons.Outlined.KeyguardView)
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = stringResource(Res.string.passkey_signature_counter),
                    )
                },
                text = {
                    val counter = state.model.counter?.toString()
                    Text(
                        text = counter
                            ?: stringResource(Res.string.empty_value),
                        color = if (counter != null) {
                            LocalContentColor.current
                        } else {
                            LocalContentColor.current
                                .combineAlpha(DisabledEmphasisAlpha)
                        },
                    )
                },
            )
        },
        trailing = {
            IconButton(
                onClick = {
                    val url = "https://www.w3.org/TR/webauthn-2/#signature-counter"
                    val intent = NavigationIntent.NavigateToBrowser(url)
                    navigationController.queue(intent)
                },
            ) {
                Icon(Icons.Outlined.Info, null)
            }
        },
        enabled = true,
    )
    FlatItemLayout(
        leading = {
            IconBox(Icons.Outlined.Public)
        },
        content = {
            FlatItemTextContent2(
                title = {
                    Text(
                        text = stringResource(Res.string.passkey_discoverable),
                    )
                },
                text = {
                    val discoverable = state.model.discoverable
                        .let { discoverable ->
                            if (discoverable) {
                                stringResource(Res.string.yes)
                            } else stringResource(Res.string.no)
                        }
                    Text(
                        text = discoverable,
                    )
                },
            )
        },
        trailing = {
            IconButton(
                onClick = {
                    val url = "https://www.w3.org/TR/webauthn-2/#discoverable-credential"
                    val intent = NavigationIntent.NavigateToBrowser(url)
                    navigationController.queue(intent)
                },
            ) {
                Icon(Icons.Outlined.Info, null)
            }
        },
        enabled = true,
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
        Text(
            text = state.createdAt,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
        )
    }
}

@Composable
private fun ColumnScope.ContentError(
    exception: Throwable,
) {
    val message = exception.localizedMessage
        ?: exception.message
        ?: "Something went wrong"
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
