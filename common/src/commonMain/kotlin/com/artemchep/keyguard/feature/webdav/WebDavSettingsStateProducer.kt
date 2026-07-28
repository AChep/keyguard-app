package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.webdav.isWebDavKeePassFileUrl
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceWebDavSettingsState(
    route: WebDavSettingsRoute,
    transmitter: RouteResultTransmitter<WebDavSettingsResult>,
): WebDavSettingsState = with(localDI().direct) {
    produceWebDavSettingsState(
        route = route,
        transmitter = transmitter,
        checkWebDavConnection = instance(),
    )
}

@Composable
fun produceWebDavSettingsState(
    route: WebDavSettingsRoute,
    transmitter: RouteResultTransmitter<WebDavSettingsResult>,
    checkWebDavConnection: CheckWebDavConnection,
): WebDavSettingsState = produceScreenState(
    key = "webdav_settings",
    initial = WebDavSettingsState(
        url = mutableStateOf(route.args.url),
        username = mutableStateOf(route.args.username),
        password = mutableStateOf(route.args.password),
        error = null,
        isTestingConnection = false,
        onSave = {},
        onTestConnection = {},
    ),
    args = arrayOf(
        route,
        checkWebDavConnection,
    ),
) {
    val testExecutor = screenExecutor()
    val errorSink = MutableStateFlow<WebDavSettingsState.Error?>(null)
    val urlState = mutableStateOf(route.args.url)
    val usernameState = mutableStateOf(route.args.username)
    val passwordState = mutableStateOf(route.args.password)

    fun buildWebDavSettingsResult() = buildWebDavSettingsResult(
        url = urlState.value,
        username = usernameState.value,
        password = passwordState.value,
        purpose = route.args.purpose,
    )

    fun onSave() {
        val result = buildWebDavSettingsResult()
        when (result) {
            is WebDavSettingsBuildResult.Failure -> {
                errorSink.value = result.error
            }

            is WebDavSettingsBuildResult.Success -> {
                transmitter(result.result)
                navigatePopSelf()
            }
        }
    }

    fun onTestConnection() {
        val result = buildWebDavSettingsResult()
        when (result) {
            is WebDavSettingsBuildResult.Failure -> {
                errorSink.value = result.error
            }

            is WebDavSettingsBuildResult.Success -> {
                errorSink.value = null
                val io = checkWebDavConnection(result.result.location).effectTap {
                    message(
                        ToastMessage(
                            title = translate(
                                Res.string.webdav_settings_test_success,
                            ),
                            type = ToastMessage.Type.SUCCESS,
                        ),
                    )
                }
                testExecutor.execute(io)
            }
        }
    }

    combine(
        errorSink,
        testExecutor.isExecutingFlow,
    ) { error, isTestingConnection ->
        WebDavSettingsState(
            url = urlState,
            username = usernameState,
            password = passwordState,
            error = error,
            isTestingConnection = isTestingConnection,
            onSave = ::onSave,
            onTestConnection = ::onTestConnection,
        )
    }
}

internal sealed class WebDavSettingsBuildResult {
    data class Success(
        val result: WebDavSettingsResult,
    ) : WebDavSettingsBuildResult()

    data class Failure(
        val error: WebDavSettingsState.Error,
    ) : WebDavSettingsBuildResult()
}

internal fun buildWebDavSettingsResult(
    url: String,
    username: String,
    password: String,
    purpose: WebDavSettingsRoute.Purpose = WebDavSettingsRoute.Purpose.Collection,
): WebDavSettingsBuildResult {
    val trimmedUrl = url.trim()
    val trimmedUsername = username.trim()
    return when {
        trimmedUrl.isEmpty() -> WebDavSettingsBuildResult.Failure(
            WebDavSettingsState.Error.UrlRequired,
        )

        trimmedUsername.isEmpty() && password.isNotEmpty() ->
            WebDavSettingsBuildResult.Failure(
                WebDavSettingsState.Error.PasswordRequiresUsername,
            )

        purpose == WebDavSettingsRoute.Purpose.KeePassDatabase &&
                !trimmedUrl.isWebDavKeePassFileUrl() ->
            WebDavSettingsBuildResult.Failure(
                WebDavSettingsState.Error.FileUrlRequired,
            )

        else -> WebDavSettingsBuildResult.Success(
            WebDavSettingsResult(
                location = when (purpose) {
                    WebDavSettingsRoute.Purpose.Collection -> WebDavLocation.Collection(
                        url = trimmedUrl,
                        credentials = WebDavCredentials.of(
                            username = trimmedUsername,
                            password = password,
                        ),
                    )

                    WebDavSettingsRoute.Purpose.KeePassDatabase -> WebDavLocation.File(
                        url = trimmedUrl,
                        credentials = WebDavCredentials.of(
                            username = trimmedUsername,
                            password = password,
                        ),
                    )
                },
            ),
        )
    }
}
