@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artemchep.keyguard.feature.androidipc

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.model.fold
import com.artemchep.keyguard.feature.EmptyView
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.home.vault.component.FlatItemLayoutExpressive
import com.artemchep.keyguard.feature.home.vault.component.FlatItemTextContent2
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.connected_crypto_apps_empty
import com.artemchep.keyguard.res.connected_crypto_apps_last_used
import com.artemchep.keyguard.res.connected_crypto_apps_registered_at
import com.artemchep.keyguard.res.connected_crypto_apps_signer
import com.artemchep.keyguard.res.connected_crypto_apps_status_not_installed
import com.artemchep.keyguard.res.connected_crypto_apps_status_registered
import com.artemchep.keyguard.res.connected_crypto_apps_status_signer_changed
import com.artemchep.keyguard.res.connected_crypto_apps_title
import com.artemchep.keyguard.res.package_name
import com.artemchep.keyguard.res.status
import com.artemchep.keyguard.ui.Avatar
import com.artemchep.keyguard.ui.FlatItemTextContent
import com.artemchep.keyguard.ui.FlatSimpleNote
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.SimpleNote
import com.artemchep.keyguard.ui.TableRowItem
import com.artemchep.keyguard.ui.icons.FaviconIcon
import com.artemchep.keyguard.ui.skeleton.SkeletonItemAvatar
import com.artemchep.keyguard.ui.skeleton.skeletonItems
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import com.artemchep.keyguard.ui.util.HorizontalDivider
import org.jetbrains.compose.resources.stringResource

@Composable
fun ConnectedCryptoAppsScreen() {
    val state = produceConnectedCryptoAppsState()
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        topBar = {
            LargeToolbar(
                title = {
                    Text(stringResource(Res.string.connected_crypto_apps_title))
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        state.fold(
            ifLoading = {
                skeletonItems(
                    avatar = SkeletonItemAvatar.LARGE,
                    count = 1,
                )
            },
            ifOk = { content ->
                if (content.apps.isEmpty()) {
                    item("empty") {
                        EmptyView(
                            text = {
                                Text(stringResource(Res.string.connected_crypto_apps_empty))
                            },
                        )
                    }
                }

                items(
                    items = content.apps,
                    key = ConnectedCryptoAppsState.App::key,
                ) { app ->
                    ConnectedCryptoAppItem(
                        modifier = Modifier
                            .animateItem(),
                        app = app,
                    )
                }
            },
        )
    }
}

@Composable
private fun ConnectedCryptoAppItem(
    modifier: Modifier = Modifier,
    app: ConnectedCryptoAppsState.App,
) {
    FlatItemLayoutExpressive(
        modifier = modifier,
        shapeState = app.shapeState,
        leading = {
            Avatar {
                FaviconIcon(
                    modifier = Modifier
                        .fillMaxSize(),
                    imageModel = {
                        AppIconUrl(app.packageName)
                    },
                )
            }
        },
        content = {
            FlatItemTextContent(
                title = {
                    Text(app.label)
                },
                text = {
                    ConnectedCryptoAppDetails(app)
                },
            )
        },
        footer = {
            val title = if (app.signerMismatch) {
                stringResource(Res.string.connected_crypto_apps_status_signer_changed)
            } else {
                stringResource(Res.string.connected_crypto_apps_signer)
            }
            FlatSimpleNote(
                modifier = Modifier
                    .padding(
                        bottom = 16.dp,
                    ),
                type = if (app.signerMismatch) SimpleNote.Type.ERROR else SimpleNote.Type.INFO,
                title = title,
                text = app.signer,
            )
        },
        trailing = {
            IconButton(
                onClick = app.onRevoke,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                )
            }
        },
        enabled = true,
    )
}

@Composable
private fun ConnectedCryptoAppDetails(
    app: ConnectedCryptoAppsState.App,
) {
    val titleWeight = 0.7f
    Column {
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(Res.string.status),
            titleWeight = titleWeight,
            text = when {
                app.signerMismatch -> stringResource(
                    Res.string.connected_crypto_apps_status_signer_changed,
                )
                !app.installed -> stringResource(
                    Res.string.connected_crypto_apps_status_not_installed,
                )
                else -> stringResource(
                    Res.string.connected_crypto_apps_status_registered,
                )
            },
        )
        HorizontalDivider()
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(Res.string.package_name),
            titleWeight = titleWeight,
            text = app.packageName,
        )
        HorizontalDivider()
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(
                Res.string.connected_crypto_apps_registered_at,
            ),
            titleWeight = titleWeight,
            text = app.registeredAt,
        )
        HorizontalDivider()
        TableRowItem(
            modifier = Modifier
                .fillMaxWidth(),
            title = stringResource(
                Res.string.connected_crypto_apps_last_used,
            ),
            titleWeight = titleWeight,
            text = app.lastUsedAt,
        )
    }
}
