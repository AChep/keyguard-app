package com.artemchep.keyguard.android

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.credentials.provider.CallingAppInfo
import com.artemchep.keyguard.common.exception.credential.CallingAppNotPrivilegedException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.io.timeout
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.usecase.AddPrivilegedApp
import com.artemchep.keyguard.common.usecase.GetPrivilegedApps
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.loading.getErrorReadableMessage
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DisabledEmphasisAlpha
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.OtherScaffold
import com.artemchep.keyguard.ui.icons.DropdownIcon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.filter
import org.jetbrains.compose.resources.getString as getComposeString
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun CredentialScaffold(
    onCancel: () -> Unit,
    titleText: String,
    subtitle: @Composable ColumnScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    ExtensionScaffold(
        header = {
            Row(
                modifier = Modifier
                    .padding(
                        start = Dimens.horizontalPadding,
                        end = 8.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .align(Alignment.CenterVertically),
                ) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current
                            .combineAlpha(MediumEmphasisAlpha),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                    ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                        subtitle()
                    }
                }

                TextButton(
                    onClick = onCancel,
                ) {
                    Icon(Icons.Outlined.Close, null)
                    Spacer(
                        modifier = Modifier
                            .width(Dimens.buttonIconPadding),
                    )
                    Text(
                        text = stringResource(Res.string.cancel),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    ) {
        content()
    }
}

@Composable
fun CredentialSubtitlePublicKey(
    modifier: Modifier = Modifier,
    username: String,
    rpId: String,
) {
    Row(
        modifier = modifier,
    ) {
        Text(
            text = username,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
        Text(
            modifier = Modifier
                .weight(1f, fill = false),
            text = "@$rpId",
            color = LocalContentColor.current
                .combineAlpha(MediumEmphasisAlpha),
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@Composable
fun CredentialSubtitlePassword(
    modifier: Modifier = Modifier,
    username: String,
) {
    Row(
        modifier = modifier,
    ) {
        Text(
            text = username,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CredentialError(
    modifier: Modifier = Modifier,
    title: String?,
    message: String,
    advanced: CredentialErrorAdvanced? = null,
    onFinish: () -> Unit,
) {
    val expandedState = remember {
        mutableStateOf(false)
    }

    OtherScaffold(
        modifier = modifier,
    ) {
        Icon(
            modifier = Modifier
                .align(Alignment.Start)
                .size(48.dp),
            imageVector = Icons.Outlined.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        if (title != null) {
            Spacer(
                modifier = Modifier
                    .height(24.dp),
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth(),
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        Text(
            modifier = Modifier
                .fillMaxWidth(),
            text = message,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (advanced != null) {
            Spacer(
                modifier = Modifier
                    .height(8.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    modifier = Modifier
                        .clickable {
                            expandedState.value = !expandedState.value
                        }
                        .padding(
                            vertical = 4.dp,
                        ),
                    text = stringResource(Res.string.advanced_options),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(
                    modifier = Modifier
                        .width(4.dp),
                )
                DropdownIcon(
                    modifier = Modifier
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                    expanded = expandedState.value,
                )
            }
            ExpandedIfNotEmpty(
                Unit.takeIf { expandedState.value },
            ) {
                Column(
                    modifier = Modifier,
                ) {
                    Spacer(
                        modifier = Modifier
                            .height(24.dp),
                    )
                    if (advanced.action != null) {
                        FlatItemLayoutExpressive(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentColor = MaterialTheme.colorScheme.error,
                            padding = PaddingValues(top = 3.dp, start = 0.dp, end = 0.dp),
                            content = {
                                Text(
                                    modifier = Modifier
                                        .fillMaxWidth(),
                                    text = advanced.action.title,
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                )
                            },
                            onClick = advanced.action.onClick,
                        )
                        Spacer(
                            modifier = Modifier
                                .height(16.dp),
                        )
                    }
                    Text(
                        modifier = Modifier
                            .fillMaxWidth(),
                        text = advanced.note,
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalContentColor.current
                            .combineAlpha(DisabledEmphasisAlpha),
                    )
                    Spacer(
                        modifier = Modifier
                            .height(8.dp),
                    )
                }
            }
        }
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
        val updatedOnFinish by rememberUpdatedState(onFinish)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(),
                onClick = {
                    updatedOnFinish()
                },
            ) {
                Text(
                    text = stringResource(Res.string.close),
                )
            }
        }
    }
}

data class UiStateError(
    val title: String? = null,
    val message: String,
    val exception: Throwable? = null,
    val advanced: CredentialErrorAdvanced? = null,
    val onFinish: () -> Unit,
)

@RequiresApi(Build.VERSION_CODES.P)
suspend fun BaseActivity.getCredentialErrorUiState(
    session: MasterSession.Key,
    callingAppInfo: CallingAppInfo,
    title: String?,
    exception: Throwable,
    beforeRetry: () -> Unit,
    onRetry: () -> Unit,
): UiStateError {
    val advanced = when (exception) {
        // If the calling app is not privileged then we
        // should show an option to add it to the list.
        is CallingAppNotPrivilegedException -> {
            val passkeyUtils = session.di.direct.instance<PasskeyUtils>()
            val addPrivilegedApp = session.di.direct.instance<AddPrivilegedApp>()
            val getPrivilegedApps = session.di.direct.instance<GetPrivilegedApps>()

            val action = CredentialErrorAdvanced.Action(
                title = getComposeString(Res.string.error_credential_calling_app_not_privileged_add_as_privileged_app_title),
                onClick = {
                    // Switch the UI state into the loading immediately, so
                    // a user knows that something's going on.
                    beforeRetry()

                    val request = passkeyUtils.callingAppPrivilegedRequest(callingAppInfo)
                    // Create a completion handler. We need to wait for the privileged apps to
                    // reflect the actual change, so when we retry the action it uses the
                    // updated list.
                    val completion = getPrivilegedApps()
                        .filter { apps ->
                            apps.any { app ->
                                app.packageName == request.packageName &&
                                        app.certFingerprintSha256 == request.certFingerprintSha256
                            }
                        }
                        .toIO()
                        .timeout(2000L)

                    addPrivilegedApp(request)
                        .attempt()
                        .effectTap {
                            completion.bind()
                            onRetry()
                        }
                        .launchIn(GlobalScope)
                },
            )
            CredentialErrorAdvanced(
                action = action,
                note = getComposeString(Res.string.error_credential_calling_app_not_privileged_add_as_privileged_app_note),
            )
        }

        else -> null
    }
    val message = kotlin.run {
        val readableMessage = getErrorReadableMessage(
            e = exception,
            translator = translatorScope,
        )
        readableMessage.title
    }

    return UiStateError(
        title = title,
        message = message,
        exception = exception,
        advanced = advanced,
        onFinish = {
            finish()
        },
    )
}

@Immutable
data class CredentialErrorAdvanced(
    val action: Action? = null,
    val note: String,
) {
    data class Action(
        val title: String,
        val onClick: () -> Unit,
    )
}

@Preview
@Composable
private fun CredentialErrorPreview(
    modifier: Modifier = Modifier,
) {
    ExtensionScaffold(
        header = {
            Text(
                modifier = Modifier
                    .padding(
                        vertical = 16.dp,
                        horizontal = 16.dp,
                    ),
                text = "Keyguard",
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        },
    ) {
        CredentialError(
            modifier = modifier,
            title = "Failed to create a passkey",
            message = "The calling app is not on the privileged list and cannot " +
                    "request authentication on behalf of the other app.",
            advanced = CredentialErrorAdvanced(
                note = "Trusting an app allows it to request credentials for any service. A malicious app could use this to try to steal your credentials.",
            ),
            onFinish = {
            },
        )
    }
}
