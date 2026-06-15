package com.artemchep.keyguard.wear.feature.passkeys

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewState
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewItem
import com.artemchep.keyguard.feature.passkeys.passkeysCredentialViewItems
import com.artemchep.keyguard.feature.passkeys.producePasskeysCredentialViewState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.empty_value
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.res.info
import com.artemchep.keyguard.res.no
import com.artemchep.keyguard.res.passkey
import com.artemchep.keyguard.res.passkey_discoverable
import com.artemchep.keyguard.res.passkey_relying_party
import com.artemchep.keyguard.res.passkey_signature_counter
import com.artemchep.keyguard.res.passkey_user_display_name
import com.artemchep.keyguard.res.passkey_user_username
import com.artemchep.keyguard.res.yes
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.icons.KeyguardPasskey
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.wear.ui.WearListCard
import com.artemchep.keyguard.wear.ui.WearListLabel
import com.artemchep.keyguard.wear.ui.WearScaffoldScreen
import com.artemchep.keyguard.wear.ui.WearSectionHeader
import com.artemchep.keyguard.wear.ui.WearKeyguardTheme
import com.artemchep.keyguard.wear.util.joinToBulletStringOrNull
import com.artemchep.keyguard.ui.surface.LocalSurfaceColor
import arrow.core.left
import arrow.core.right
import com.artemchep.keyguard.feature.auth.bitwarden.twofactor.BitwardenLoginTwofaState
import com.artemchep.keyguard.wear.ui.WearListAction
import com.artemchep.keyguard.wear.ui.WearScaffoldLoader
import kotlin.time.Instant.Companion.parse
import org.jetbrains.compose.resources.stringResource

@Composable
fun WearPasskeysCredentialViewScreen(
    args: PasskeysCredentialViewRoute.Args,
) {
    val loadableState = producePasskeysCredentialViewState(
        args = args,
        mode = LocalAppMode.current,
    )
    WearPasskeysCredentialViewScreen(
        loadableState = loadableState,
    )
}

@Composable
private fun WearPasskeysCredentialViewScreen(
    loadableState: Loadable<PasskeysCredentialViewState>,
) {
    WearScaffoldScreen(
        icon = Icons.Outlined.KeyguardPasskey,
        title = stringResource(Res.string.passkey),
        overlay = {
            WearScaffoldLoader(
                modifier = Modifier,
                visible = loadableState is Loadable.Loading,
            )
        },
    ) { transformationSpec ->
        when (loadableState) {
            is Loadable.Loading -> {
                // Do nothing
            }

            is Loadable.Ok -> {
                loadableState.value.content.fold(
                    ifLeft = { error ->
                        item("error") {
                            val text = error.localizedMessage
                                ?: error.message
                                ?: stringResource(Res.string.error_failed_unknown)
                            WearListLabel(
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec),
                                text = text,
                                transformation = SurfaceTransformation(transformationSpec),
                            )
                        }
                    },
                    ifRight = { state ->
                        WearPasskeysCredentialViewContent(
                            state = state,
                            transformationSpec = transformationSpec,
                        )
                    },
                )
            }
        }
    }
}

private fun TransformingLazyColumnScope.WearPasskeysCredentialViewContent(
    state: PasskeysCredentialViewState.Content,
    transformationSpec: TransformationSpec,
) {
    state.passkeysCredentialViewItems().forEach { row ->
        item(row.key) {
            when (row) {
                is PasskeysCredentialViewItem.Text -> {
                    WearPasskeysCredentialTextItem(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        icon = {
                            Icon(
                                imageVector = row.icon,
                                contentDescription = null,
                            )
                        },
                        title = stringResource(row.title),
                        value = row.value,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is PasskeysCredentialViewItem.Section -> {
                    WearSectionHeader(
                        title = stringResource(row.title),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is PasskeysCredentialViewItem.RelyingParty -> {
                    WearListAction(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        title = stringResource(Res.string.passkey_relying_party),
                        text = joinToBulletStringOrNull(
                            row.rpName,
                            row.rpId,
                        ) ?: stringResource(Res.string.empty_value),
                        leading = {
                            Icon(
                                imageVector = row.icon,
                                contentDescription = null,
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is PasskeysCredentialViewItem.SignatureCounter -> {
                    WearListAction(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        title = stringResource(Res.string.passkey_signature_counter),
                        text = row.counter
                            ?.toString()
                            ?: stringResource(Res.string.empty_value),
                        leading = {
                            Icon(
                                imageVector = row.icon,
                                contentDescription = null,
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is PasskeysCredentialViewItem.Discoverable -> {
                    WearListAction(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        title = stringResource(Res.string.passkey_discoverable),
                        text = if (row.value) {
                            stringResource(Res.string.yes)
                        } else {
                            stringResource(Res.string.no)
                        },
                        leading = {
                            Icon(
                                imageVector = row.icon,
                                contentDescription = null,
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }

                is PasskeysCredentialViewItem.CreatedAt -> {
                    WearListLabel(
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec),
                        text = row.text,
                        transformation = SurfaceTransformation(transformationSpec),
                    )
                }
            }
        }
    }
}

@Composable
private fun WearPasskeysCredentialTextItem(
    modifier: Modifier = Modifier,
    icon: @Composable (RowScope.() -> Unit)? = null,
    title: String,
    value: String?,
    transformation: SurfaceTransformation?,
) {
    WearListCard(
        modifier = modifier,
        icon = icon,
        title = {
            Text(text = title)
        },
        text = {
            Text(
                text = value
                    ?: stringResource(Res.string.empty_value),
                color = if (value != null) {
                    LocalContentColor.current
                } else {
                    LocalContentColor.current
                        .combineAlpha(MediumEmphasisAlpha)
                },
            )
        },
        transformation = transformation,
    )
}

@Composable
private fun WearPasskeysCredentialViewPreviewHost(
    loadableState: Loadable<PasskeysCredentialViewState>,
) {
    WearKeyguardTheme {
        val containerColor = MaterialTheme.colorScheme.background
        Box(
            modifier = Modifier
                .background(containerColor),
        ) {
            CompositionLocalProvider(
                LocalSurfaceColor provides containerColor,
            ) {
                WearPasskeysCredentialViewScreen(
                    loadableState = loadableState,
                )
            }
        }
    }
}

private fun previewCredential(
    rpName: String?,
    userName: String?,
    userDisplayName: String?,
    counter: Int?,
    discoverable: Boolean,
) = DSecret.Login.Fido2Credentials(
    credentialId = "nL4x4P2p5wJ8C1s9xqR7kA",
    keyType = "public-key",
    keyAlgorithm = "ECDSA",
    keyCurve = "P-256",
    keyValue = "04b8d57e8af6c21d53b9f7a4115d8e7f",
    rpId = "accounts.example.com",
    rpName = rpName,
    counter = counter,
    userHandle = "c29tZS11c2VyLWhhbmRsZQ",
    userName = userName,
    userDisplayName = userDisplayName,
    discoverable = discoverable,
    creationDate = parse("2026-04-13T12:34:56Z"),
)

private fun previewContent(
    rpName: String?,
    userName: String?,
    userDisplayName: String?,
    counter: Int?,
    discoverable: Boolean,
) = PasskeysCredentialViewState.Content(
    model = previewCredential(
        rpName = rpName,
        userName = userName,
        userDisplayName = userDisplayName,
        counter = counter,
        discoverable = discoverable,
    ),
    createdAt = "Created Apr 13, 2026 at 12:34",
)

@Preview
@Composable
private fun WearPasskeysCredentialViewLoadingPreview() {
    WearPasskeysCredentialViewPreviewHost(
        loadableState = Loadable.Loading,
    )
}

@Preview
@Composable
private fun WearPasskeysCredentialViewContentFullPreview() {
    WearPasskeysCredentialViewPreviewHost(
        loadableState = Loadable.Ok(
            PasskeysCredentialViewState(
                content = previewContent(
                    rpName = "Example",
                    userName = "alex@example.com",
                    userDisplayName = "Alex Example",
                    counter = 42,
                    discoverable = true,
                ).right(),
            ),
        ),
    )
}

@Preview
@Composable
private fun WearPasskeysCredentialViewContentMissingOptionalFieldsPreview() {
    WearPasskeysCredentialViewPreviewHost(
        loadableState = Loadable.Ok(
            PasskeysCredentialViewState(
                content = previewContent(
                    rpName = null,
                    userName = null,
                    userDisplayName = null,
                    counter = null,
                    discoverable = false,
                ).right(),
            ),
        ),
    )
}

@Preview
@Composable
private fun WearPasskeysCredentialViewErrorPreview() {
    WearPasskeysCredentialViewPreviewHost(
        loadableState = Loadable.Ok(
            PasskeysCredentialViewState(
                content = IllegalStateException("Preview error: failed to load passkey.").left(),
            ),
        ),
    )
}
