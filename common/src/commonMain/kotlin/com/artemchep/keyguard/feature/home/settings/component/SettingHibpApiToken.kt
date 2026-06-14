package com.artemchep.keyguard.feature.home.settings.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.CheckHibpApiToken
import com.artemchep.keyguard.common.usecase.GetHibpApiToken
import com.artemchep.keyguard.common.usecase.PutHibpApiToken
import com.artemchep.keyguard.common.usecase.ShowMessage
import com.artemchep.keyguard.common.usecase.WindowCoroutineScope
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.common.VisibilityState
import com.artemchep.keyguard.feature.auth.common.VisibilityToggle
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationRouteFactory
import com.artemchep.keyguard.feature.confirmation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.home.settings.KgAction
import com.artemchep.keyguard.feature.home.settings.LocalSettingPaneComponents
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.pref_item_hibp_api_token_error
import com.artemchep.keyguard.res.pref_item_hibp_api_token_field_label
import com.artemchep.keyguard.res.pref_item_hibp_api_token_status_checking
import com.artemchep.keyguard.res.pref_item_hibp_api_token_status_failed
import com.artemchep.keyguard.res.pref_item_hibp_api_token_status_rejected
import com.artemchep.keyguard.res.pref_item_hibp_api_token_status_verified
import com.artemchep.keyguard.res.pref_item_hibp_api_token_text
import com.artemchep.keyguard.res.pref_item_hibp_api_token_title
import com.artemchep.keyguard.ui.ExpandedIfNotEmpty
import com.artemchep.keyguard.ui.ExpandedIfNotEmptyForRow
import com.artemchep.keyguard.ui.FlatTextFieldBadge
import com.artemchep.keyguard.ui.animatedConcealedText
import com.artemchep.keyguard.ui.icons.icon
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.info
import com.artemchep.keyguard.ui.theme.monoFontFamily
import com.artemchep.keyguard.ui.theme.ok
import com.artemchep.keyguard.ui.tooltip.Tooltip
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.kodein.di.DirectDI
import org.kodein.di.instance

private val HIBP_API_TOKEN_REGEX = Regex("^[0-9a-fA-F]{32}$")

private const val HIBP_API_TOKEN_ITEM_KEY = "hibp_api_token"

private enum class HibpApiTokenCheckState {
    Checking,
    Verified,
    Rejected,
    Failed,
}

fun settingHibpApiTokenProvider(
    directDI: DirectDI,
) = settingHibpApiTokenProvider(
    getHibpApiToken = directDI.instance(),
    putHibpApiToken = directDI.instance(),
    checkHibpApiToken = directDI.instance(),
    confirmationRouteFactory = directDI.instance(),
    showMessage = directDI.instance(),
    windowCoroutineScope = directDI.instance(),
)

fun settingHibpApiTokenProvider(
    getHibpApiToken: GetHibpApiToken,
    putHibpApiToken: PutHibpApiToken,
    checkHibpApiToken: CheckHibpApiToken,
    confirmationRouteFactory: ConfirmationRouteFactory,
    showMessage: ShowMessage,
    windowCoroutineScope: WindowCoroutineScope,
): SettingComponent = getHibpApiToken()
    .distinctUntilChanged()
    .flatMapLatest { apiToken ->
        hibpApiTokenCheckStateFlow(
            apiToken = apiToken,
            checkHibpApiToken = checkHibpApiToken,
        ).map { apiTokenCheckState ->
            val onTokenChange = { token: String? ->
                putHibpApiToken(token)
                    .launchIn(windowCoroutineScope)
                Unit
            }
            val onTokenError = { error: String ->
                val message = ToastMessage(
                    title = error,
                    type = ToastMessage.Type.ERROR,
                )
                showMessage.copy(message)
                Unit
            }

            SettingIi(
                search = SettingIi.Search(
                    group = "watchtower",
                    tokens = listOf(
                        "hibp",
                        "haveibeenpwned",
                        "pwned",
                        "breach",
                        "token",
                        "api",
                    ),
                ),
            ) {
                SettingHibpApiToken(
                    apiToken = apiToken,
                    apiTokenCheckState = apiTokenCheckState,
                    confirmationRouteFactory = confirmationRouteFactory,
                    onTokenChange = onTokenChange,
                    onTokenError = onTokenError,
                )
            }
        }
    }

private fun hibpApiTokenCheckStateFlow(
    apiToken: String?,
    checkHibpApiToken: CheckHibpApiToken,
): Flow<HibpApiTokenCheckState?> {
    val token = apiToken
        ?.takeIf { it.isNotBlank() }
        ?: return flowOf(null)

    return flow {
        emit(HibpApiTokenCheckState.Checking)

        val result = checkHibpApiToken(token)
            .attempt()
            .bind()
        val state = result.fold(
            ifLeft = { e ->
                when {
                    e is HttpException && e.statusCode == HttpStatusCode.Unauthorized ->
                        HibpApiTokenCheckState.Rejected

                    else -> HibpApiTokenCheckState.Failed
                }
            },
            ifRight = {
                HibpApiTokenCheckState.Verified
            },
        )
        emit(state)
    }
}

@Composable
private fun HibpApiTokenFooter(
    token: String,
    checkState: HibpApiTokenCheckState?,
) {
    val visibilityState = remember(token) {
        VisibilityState(isVisible = false)
    }
    val tokenText = remember(token) {
        AnnotatedString(token)
    }
    val shownToken = animatedConcealedText(
        text = tokenText,
        concealed = !visibilityState.isVisible,
    )

    Column(
        modifier = Modifier
            .padding(
                start = Dimens.contentPadding + Dimens.horizontalPadding * 1 + 24.dp,
                end = Dimens.contentPadding,
                top = 4.dp,
                bottom = 4.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f),
            ) {
                Text(
                    text = shownToken,
                    fontFamily = monoFontFamily,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                ExpandedIfNotEmpty(
                    valueOrNull = checkState
                        .takeIf { visibilityState.isVisible },
                ) { state ->
                    HibpApiTokenCheckStatusBadge(state)
                }
            }

            Spacer(
                modifier = Modifier
                    .width(8.dp),
            )

            ExpandedIfNotEmptyForRow(
                valueOrNull = checkState
                    .takeIf { !visibilityState.isVisible },
            ) { state ->
                HibpApiTokenCheckStatusIcon(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(24.dp),
                    state = state,
                )
            }
            VisibilityToggle(
                visibilityState = visibilityState,
            )
        }
    }
}

@Composable
private fun HibpApiTokenCheckStatusBadge(
    state: HibpApiTokenCheckState,
) {
    val type = when (state) {
        HibpApiTokenCheckState.Checking -> TextFieldModel2.Vl.Type.INFO
        HibpApiTokenCheckState.Verified -> TextFieldModel2.Vl.Type.SUCCESS
        HibpApiTokenCheckState.Rejected,
        HibpApiTokenCheckState.Failed,
            -> TextFieldModel2.Vl.Type.ERROR
    }
    val text = rememberHibpApiTokenCheckStatusText(state)

    FlatTextFieldBadge(
        type = type,
        text = text,
    )
}

@Composable
private fun HibpApiTokenCheckStatusIcon(
    modifier: Modifier = Modifier,
    state: HibpApiTokenCheckState,
) {
    val color = when (state) {
        HibpApiTokenCheckState.Checking -> MaterialTheme.colorScheme.info
        HibpApiTokenCheckState.Verified -> MaterialTheme.colorScheme.ok
        HibpApiTokenCheckState.Rejected,
        HibpApiTokenCheckState.Failed,
            -> MaterialTheme.colorScheme.error
    }
    val icon = when (state) {
        HibpApiTokenCheckState.Checking -> {
            // Instead of an icon show the loading
            // composable component.
            CircularProgressIndicator(
                modifier = modifier,
            )
            return
        }

        HibpApiTokenCheckState.Verified,
            -> Icons.Outlined.Check

        HibpApiTokenCheckState.Rejected,
        HibpApiTokenCheckState.Failed,
            -> Icons.Outlined.ErrorOutline
    }
    val text = rememberHibpApiTokenCheckStatusText(state)

    Tooltip(
        modifier = modifier,
        valueOrNull = text,
        tooltip = { t ->
            Text(t)
        },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
        )
    }
}

@Composable
private fun rememberHibpApiTokenCheckStatusText(state: HibpApiTokenCheckState): String {
    val res = when (state) {
        HibpApiTokenCheckState.Checking ->
            Res.string.pref_item_hibp_api_token_status_checking

        HibpApiTokenCheckState.Verified ->
            Res.string.pref_item_hibp_api_token_status_verified

        HibpApiTokenCheckState.Rejected ->
            Res.string.pref_item_hibp_api_token_status_rejected

        HibpApiTokenCheckState.Failed ->
            Res.string.pref_item_hibp_api_token_status_failed
    }
    return stringResource(res)
}

@Composable
private fun SettingHibpApiToken(
    apiToken: String?,
    apiTokenCheckState: HibpApiTokenCheckState?,
    confirmationRouteFactory: ConfirmationRouteFactory,
    onTokenChange: (String?) -> Unit,
    onTokenError: (String) -> Unit,
) {
    val navigationController by rememberUpdatedState(LocalNavigationController.current)
    val updatedApiToken by rememberUpdatedState(apiToken)
    val updatedConfirmationRouteFactory by rememberUpdatedState(confirmationRouteFactory)
    val updatedOnTokenChange by rememberUpdatedState(onTokenChange)
    val updatedOnTokenError by rememberUpdatedState(onTokenError)

    val title = stringResource(Res.string.pref_item_hibp_api_token_title)
    val text = stringResource(Res.string.pref_item_hibp_api_token_text)
    val error = stringResource(Res.string.pref_item_hibp_api_token_error)

    val fieldLabel = stringResource(Res.string.pref_item_hibp_api_token_field_label)
    LocalSettingPaneComponents.current.KgAction(
        icon = Icons.Outlined.Key,
        title = title,
        text = text,
        onClick = {
            val route = updatedConfirmationRouteFactory.registerRouteResultReceiver(
                args = ConfirmationRoute.Args(
                    icon = icon(Icons.Outlined.Key),
                    title = title,
                    message = text,
                    items = listOf(
                        ConfirmationRoute.Args.Item.StringItem(
                            key = HIBP_API_TOKEN_ITEM_KEY,
                            value = updatedApiToken.orEmpty(),
                            title = fieldLabel,
                            type = ConfirmationRoute.Args.Item.StringItem.Type.Token,
                            canBeEmpty = true,
                        ),
                    ),
                ),
            ) { result ->
                if (result !is ConfirmationResult.Confirm) {
                    return@registerRouteResultReceiver
                }

                val token = result.data[HIBP_API_TOKEN_ITEM_KEY] as? String
                    ?: return@registerRouteResultReceiver
                val normalizedToken = token.trim()
                when {
                    normalizedToken.isEmpty() ->
                        updatedOnTokenChange(null)

                    HIBP_API_TOKEN_REGEX.matches(normalizedToken) ->
                        updatedOnTokenChange(normalizedToken)

                    else -> updatedOnTokenError(error)
                }
            }
            val intent = NavigationIntent.NavigateToRoute(
                route = route,
            )
            navigationController.queue(intent)
        },
        footer = {
            ExpandedIfNotEmpty(
                valueOrNull = apiToken
                    ?.takeIf { it.isNotBlank() },
            ) { token ->
                HibpApiTokenFooter(
                    token = token,
                    checkState = apiTokenCheckState,
                )
            }
        },
    )
}
