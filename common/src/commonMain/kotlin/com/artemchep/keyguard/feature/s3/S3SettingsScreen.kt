package com.artemchep.keyguard.feature.s3

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.PLACEHOLDER_S3_BUCKET
import com.artemchep.keyguard.PLACEHOLDER_S3_KEEPASS_KEY
import com.artemchep.keyguard.PLACEHOLDER_S3_PREFIX
import com.artemchep.keyguard.PLACEHOLDER_S3_REGION
import com.artemchep.keyguard.PLACEHOLDER_URL_S3_ENDPOINT
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.service.permission.PermissionState
import com.artemchep.keyguard.feature.home.vault.component.FlatItemSimpleExpressive
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.permissions.LocalNetworkPermissionNote
import com.artemchep.keyguard.feature.permissions.rememberLocalNetworkPermission
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.s3_settings_access_key_id_title
import com.artemchep.keyguard.res.s3_settings_auth_note
import com.artemchep.keyguard.res.s3_settings_bucket_title
import com.artemchep.keyguard.res.s3_settings_endpoint_note
import com.artemchep.keyguard.res.s3_settings_endpoint_title
import com.artemchep.keyguard.res.s3_settings_header_title
import com.artemchep.keyguard.res.s3_settings_key_title
import com.artemchep.keyguard.res.s3_settings_path_style_text
import com.artemchep.keyguard.res.s3_settings_path_style_title
import com.artemchep.keyguard.res.s3_settings_prefix_title
import com.artemchep.keyguard.res.s3_settings_region_title
import com.artemchep.keyguard.res.s3_settings_secret_access_key_title
import com.artemchep.keyguard.res.save
import com.artemchep.keyguard.res.webdav_settings_browse_title
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.button.connectionTestItems
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun S3SettingsScreen(
    route: S3SettingsRoute,
    transmitter: RouteResultTransmitter<S3SettingsResult>,
) {
    val state = produceS3SettingsState(
        route = route,
        transmitter = transmitter,
    )
    S3SettingsContent(
        state = state,
        purpose = route.args.purpose,
        keePassMode = route.args.keePassMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun S3SettingsContent(
    state: S3SettingsState,
    purpose: S3SettingsRoute.Purpose,
    keePassMode: S3SettingsRoute.KeePassMode,
) {
    // Only an existing database is checked with a read; anything else
    // is checked by writing and removing a probe.
    val readOnlyTest = purpose == S3SettingsRoute.Purpose.KeePassDatabase &&
        keePassMode == S3SettingsRoute.KeePassMode.Open

    val localNetworkPermission = rememberLocalNetworkPermission()
    val scrollBehavior = ToolbarBehavior.behavior()
    ScaffoldLazyColumn(
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        expressive = true,
        topAppBarScrollBehavior = scrollBehavior,
        floatingActionState = rememberUpdatedState(
            FabState(
                onClick = state.onSave
                    .takeUnless { state.isTestingConnection },
                model = null,
            ),
        ),
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
                        text = stringResource(Res.string.save),
                    )
                },
            )
        },
        topBar = {
            LargeToolbar(
                title = {
                    Text(
                        text = stringResource(Res.string.s3_settings_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        s3EndpointItems(state)
        s3LocationItems(state, purpose)
        s3AccessKeyItems(state)
        s3OptionItems(state, localNetworkPermission, readOnlyTest)
    }
}

private fun LazyListScope.s3EndpointItems(
    state: S3SettingsState,
) {
    item("endpoint") {
        UrlFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.s3_settings_endpoint_title),
            value = state.endpoint.toTextFieldModel(
                hint = PLACEHOLDER_URL_S3_ENDPOINT,
                error = state.error.textIfOwnedBy(S3FormError.EndpointInvalid),
            ),
            shapeState = ShapeState.ALL,
            clearButton = true,
        )
    }
    note("endpoint.note") {
        stringResource(Res.string.s3_settings_endpoint_note)
    }
}

private fun LazyListScope.s3LocationItems(
    state: S3SettingsState,
    purpose: S3SettingsRoute.Purpose,
) {
    item("region") {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.s3_settings_region_title),
            value = state.region.toTextFieldModel(
                hint = PLACEHOLDER_S3_REGION,
            ),
            shapeState = ShapeState.START,
            singleLine = true,
            clearButton = true,
        )
    }
    gap("region.gap")
    item("bucket") {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.s3_settings_bucket_title),
            value = state.bucket.toTextFieldModel(
                hint = PLACEHOLDER_S3_BUCKET,
                error = state.error.textIfOwnedBy(S3FormError.BucketRequired, S3FormError.BucketInvalid),
            ),
            shapeState = ShapeState.CENTER,
            singleLine = true,
            clearButton = true,
        )
    }
    gap("bucket.gap")
    s3PathItem(state, purpose)
}

private fun LazyListScope.s3PathItem(
    state: S3SettingsState,
    purpose: S3SettingsRoute.Purpose,
) {
    item("path") {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = when (purpose) {
                S3SettingsRoute.Purpose.Prefix -> stringResource(Res.string.s3_settings_prefix_title)
                S3SettingsRoute.Purpose.KeePassDatabase -> stringResource(Res.string.s3_settings_key_title)
            },
            value = state.path.toTextFieldModel(
                hint = when (purpose) {
                    S3SettingsRoute.Purpose.Prefix -> PLACEHOLDER_S3_PREFIX
                    S3SettingsRoute.Purpose.KeePassDatabase -> PLACEHOLDER_S3_KEEPASS_KEY
                },
                error = state.error.textIfOwnedBy(
                    S3FormError.PrefixInvalid,
                    S3FormError.KeyRequired,
                    S3FormError.KeyInvalid,
                    S3FormError.KeyExtensionRequired,
                ),
            ),
            shapeState = ShapeState.END,
            singleLine = true,
            clearButton = true,
            trailing = {
                IconButton(
                    onClick = state.onBrowse,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FolderOpen,
                        contentDescription = stringResource(
                            Res.string.webdav_settings_browse_title,
                        ),
                    )
                }
            },
        )
    }
}

private fun LazyListScope.s3AccessKeyItems(
    state: S3SettingsState,
) {
    note("auth.header", header = true) {
        stringResource(Res.string.s3_settings_auth_note)
    }
    item("auth.access_key_id") {
        FlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            leading = {
                IconBox(
                    main = Icons.Outlined.Key,
                )
            },
            label = stringResource(Res.string.s3_settings_access_key_id_title),
            value = state.accessKeyId.toTextFieldModel(
                error = state.error.textIfOwnedBy(S3FormError.AccessKeyIdRequired, S3FormError.AccessKeyIdInvalid),
            ),
            shapeState = ShapeState.START,
            singleLine = true,
            clearButton = true,
        )
    }
    gap("auth.gap")
    item("auth.secret_access_key") {
        PasswordFlatTextField(
            modifier = Modifier
                .padding(horizontal = Dimens.fieldHorizontalPadding),
            label = stringResource(Res.string.s3_settings_secret_access_key_title),
            value = state.secretAccessKey.toTextFieldModel(
                error = state.error.textIfOwnedBy(S3FormError.SecretAccessKeyRequired),
            ),
            shapeState = ShapeState.END,
            clearButton = true,
        )
    }
}

private fun LazyListScope.s3OptionItems(
    state: S3SettingsState,
    localNetworkPermission: PermissionState.Declined?,
    readOnlyTest: Boolean,
) {
    item("path_style.spacer") {
        Spacer(
            modifier = Modifier
                .height(16.dp),
        )
    }
    item("path_style") {
        FlatItemSimpleExpressive(
            title = {
                Text(
                    text = stringResource(Res.string.s3_settings_path_style_title),
                )
            },
            text = {
                Text(
                    text = stringResource(Res.string.s3_settings_path_style_text),
                )
            },
            trailing = {
                Switch(
                    checked = state.pathStyle.value,
                    onCheckedChange = null,
                )
            },
            onClick = {
                state.pathStyle.value = !state.pathStyle.value
            },
        )
    }
    localNetworkPermission?.let { permission ->
        item("local_network_permission") {
            LocalNetworkPermissionNote(
                permission = permission,
            )
        }
    }
    connectionTestItems(
        onClick = state.onTestConnection,
        enabled = !state.isTestingConnection,
        readOnly = readOnlyTest,
    )
    item("bottom.spacer") {
        Spacer(
            modifier = Modifier
                .height(80.dp),
        )
    }
}

