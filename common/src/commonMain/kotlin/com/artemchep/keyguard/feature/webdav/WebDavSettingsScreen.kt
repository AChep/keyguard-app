package com.artemchep.keyguard.feature.webdav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivateConnectivity
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artemchep.keyguard.common.model.ShapeState
import com.artemchep.keyguard.common.model.UsernameVariation
import com.artemchep.keyguard.common.model.icon
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.navigation.NavigationIcon
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.DefaultFab
import com.artemchep.keyguard.ui.FabState
import com.artemchep.keyguard.ui.FlatTextField
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.PasswordFlatTextField
import com.artemchep.keyguard.ui.ScaffoldLazyColumn
import com.artemchep.keyguard.ui.UrlFlatTextField
import com.artemchep.keyguard.ui.icons.IconBox
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.theme.verticalPaddingHalf
import com.artemchep.keyguard.ui.toolbar.LargeToolbar
import com.artemchep.keyguard.ui.toolbar.util.ToolbarBehavior
import org.jetbrains.compose.resources.stringResource

@Composable
fun WebDavSettingsScreen(
    route: WebDavSettingsRoute,
    transmitter: RouteResultTransmitter<WebDavSettingsResult>,
) {
    val state = produceWebDavSettingsState(
        route = route,
        transmitter = transmitter,
    )
    WebDavSettingsContent(
        state = state,
        purpose = route.args.purpose,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WebDavSettingsContent(
    state: WebDavSettingsState,
    purpose: WebDavSettingsRoute.Purpose,
) {
    val urlRequiredError =
        stringResource(Res.string.error_webdav_url_required)
    val fileUrlRequiredError =
        stringResource(Res.string.error_webdav_file_url_required)
    val passwordRequiresUsernameError =
        stringResource(Res.string.webdav_settings_password_requires_username_error)
    val urlError = when (state.error) {
        WebDavSettingsState.Error.UrlRequired -> urlRequiredError
        WebDavSettingsState.Error.FileUrlRequired -> fileUrlRequiredError
        else -> null
    }
    val passwordError = when (state.error) {
        WebDavSettingsState.Error.PasswordRequiresUsername ->
            passwordRequiresUsernameError

        else -> null
    }

    val probeText = when (purpose) {
        WebDavSettingsRoute.Purpose.Collection ->
            stringResource(Res.string.webdav_settings_test_text)
        WebDavSettingsRoute.Purpose.KeePassDatabase ->
            stringResource(Res.string.webdav_settings_test_text_read_only)
    }

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
                        text = stringResource(Res.string.webdav_settings_header_title),
                    )
                },
                navigationIcon = {
                    NavigationIcon()
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) {
        item("url") {
            val url = TextFieldModel(
                text = state.url.value,
                error = urlError,
                hint = when (purpose) {
                    WebDavSettingsRoute.Purpose.Collection -> "https://example.com/keyguard-backups/"
                    WebDavSettingsRoute.Purpose.KeePassDatabase -> "https://example.com/keyguard.kdbx"
                },
                onChange = state.url::value::set,
            )
            UrlFlatTextField(
                modifier = Modifier
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                label = stringResource(Res.string.webdav_settings_url_title),
                value = url,
                shapeState = ShapeState.ALL,
                clearButton = true,
            )
        }
        item("auth.header") {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.textHorizontalPadding,
                        vertical = Dimens.verticalPadding,
                    ),
                text = stringResource(Res.string.webdav_settings_auth_note),
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
            )
        }
        item("auth.username") {
            val username = TextFieldModel(
                text = state.username.value,
                onChange = state.username::value::set,
            )
            FlatTextField(
                modifier = Modifier
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                leading = {
                    IconBox(
                        main = UsernameVariation.USERNAME.icon,
                    )
                },
                label = stringResource(Res.string.username),
                value = username,
                shapeState = ShapeState.START,
                singleLine = true,
                clearButton = true,
            )
        }
        item("auth.gap") {
            Spacer(
                modifier = Modifier
                    .height(3.dp),
            )
        }
        item("auth.password") {
            val password = TextFieldModel(
                text = state.password.value,
                error = passwordError,
                onChange = state.password::value::set,
            )
            PasswordFlatTextField(
                modifier = Modifier
                    .padding(horizontal = Dimens.fieldHorizontalPadding),
                value = password,
                shapeState = ShapeState.END,
                clearButton = true,
            )
        }
        item("connection.header") {
            Spacer(
                modifier = Modifier
                    .height(32.dp),
            )
        }
        item("connection.test") {
            val colors = ButtonDefaults.buttonColors()
            val elevation = ButtonDefaults.buttonElevation()
            Button(
                modifier = Modifier
                    .padding(horizontal = Dimens.buttonHorizontalPadding),
                onClick = state.onTestConnection,
                colors = colors,
                shapes = ButtonDefaults.shapes(),
                elevation = elevation,
                enabled = !state.isTestingConnection,
            ) {
                Box(
                    modifier = Modifier
                        .size(ButtonDefaults.IconSize),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PrivateConnectivity,
                        contentDescription = null,
                    )
                }
                Spacer(
                    modifier = Modifier
                        .width(ButtonDefaults.IconSpacing),
                )
                Text(
                    text = stringResource(Res.string.webdav_settings_test_title),
                )
            }
        }
        item("connection.test.info") {
            Text(
                modifier = Modifier
                    .padding(
                        horizontal = Dimens.textHorizontalPadding,
                        vertical = Dimens.verticalPaddingHalf,
                    ),
                text = probeText,
                color = LocalContentColor.current
                    .combineAlpha(MediumEmphasisAlpha),
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp,
            )
        }
        item("bottom.spacer") {
            Spacer(
                modifier = Modifier
                    .height(80.dp),
            )
        }
    }
}
