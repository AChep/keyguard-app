package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.model.WebDavCredentials
import com.artemchep.keyguard.common.model.WebDavLocation
import com.artemchep.keyguard.common.service.webdav.isWebDavKeePassFileUrl
import com.artemchep.keyguard.common.service.webdav.parseWebDavKeePassFileUrlOrNull
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.util.webdav.resolveWebDavResourceUrl
import com.artemchep.keyguard.util.webdav.webDavRelativePathOrNull
import io.ktor.http.Url
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
        onUrlChange = {},
        onBrowse = {},
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
    var browseRootUrl: String? = null

    fun onUrlChange(value: String) {
        browseRootUrl = null
        errorSink.value = null
        urlState.value = value
    }

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
                val location = webDavConnectionTestLocation(
                    location = result.result.location,
                    purpose = route.args.purpose,
                    keePassMode = route.args.keePassMode,
                )
                val io = checkWebDavConnection(location).effectTap {
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

    fun onBrowse() {
        val browseResult = buildWebDavPickerArgs(
            url = urlState.value,
            username = usernameState.value,
            password = passwordState.value,
            purpose = route.args.purpose,
            keePassMode = route.args.keePassMode,
            browseRootUrl = browseRootUrl,
        )
        when (browseResult) {
            is WebDavPickerArgsBuildResult.Failure -> {
                errorSink.value = browseResult.error
            }

            is WebDavPickerArgsBuildResult.Success -> {
                errorSink.value = null
                browseRootUrl = browseResult.rootUrl
                val pickerRoute = registerRouteResultReceiver(
                    route = WebDavPickerRoute(
                        args = browseResult.args,
                    ),
                ) { pickerResult ->
                    urlState.value = pickerResult.url
                    errorSink.value = null
                }
                navigate(
                    NavigationIntent.NavigateToRoute(pickerRoute),
                )
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
            onUrlChange = ::onUrlChange,
            onBrowse = ::onBrowse,
            onSave = ::onSave,
            onTestConnection = ::onTestConnection,
        )
    }
}

internal sealed interface WebDavPickerArgsBuildResult {
    data class Success(
        val rootUrl: String,
        val args: WebDavPickerRoute.Args,
    ) : WebDavPickerArgsBuildResult

    data class Failure(
        val error: WebDavSettingsState.Error,
    ) : WebDavPickerArgsBuildResult
}

internal fun buildWebDavPickerArgs(
    url: String,
    username: String,
    password: String,
    purpose: WebDavSettingsRoute.Purpose,
    keePassMode: WebDavSettingsRoute.KeePassMode,
    browseRootUrl: String?,
): WebDavPickerArgsBuildResult {
    val trimmedUrl = url.trim()
    val validationError = validateWebDavFormInput(
        url = trimmedUrl,
        username = username.trim(),
        password = password,
    )
    if (validationError != null) {
        return WebDavPickerArgsBuildResult.Failure(
            validationError,
        )
    }

    val pickerMode = webDavPickerMode(purpose, keePassMode)
    val existingRoot = browseRootUrl?.let(::normalizeWebDavBrowseRootOrNull)
    return if (existingRoot != null) {
        buildWebDavPickerArgsFromExistingRoot(
            rootUrl = existingRoot,
            resourceUrl = trimmedUrl,
            username = username,
            password = password,
            purpose = purpose,
            pickerMode = pickerMode,
        )
    } else {
        buildWebDavPickerArgsFromEnteredUrl(
            url = trimmedUrl,
            username = username,
            password = password,
            purpose = purpose,
            pickerMode = pickerMode,
        )
    }
}

private fun validateWebDavFormInput(
    url: String,
    username: String,
    password: String,
): WebDavSettingsState.Error? = when {
    url.isEmpty() -> WebDavSettingsState.Error.UrlRequired
    username.isEmpty() && password.isNotEmpty() ->
        WebDavSettingsState.Error.PasswordRequiresUsername
    else -> null
}

private fun webDavPickerMode(
    purpose: WebDavSettingsRoute.Purpose,
    keePassMode: WebDavSettingsRoute.KeePassMode,
): WebDavPickerRoute.Mode = when (purpose) {
    WebDavSettingsRoute.Purpose.Collection ->
        WebDavPickerRoute.Mode.SelectCollection

    WebDavSettingsRoute.Purpose.KeePassDatabase -> when (keePassMode) {
        WebDavSettingsRoute.KeePassMode.Open ->
            WebDavPickerRoute.Mode.OpenKeePassDatabase

        WebDavSettingsRoute.KeePassMode.Create ->
            WebDavPickerRoute.Mode.CreateKeePassDatabase
    }
}

private fun buildWebDavPickerArgsFromExistingRoot(
    rootUrl: String,
    resourceUrl: String,
    username: String,
    password: String,
    purpose: WebDavSettingsRoute.Purpose,
    pickerMode: WebDavPickerRoute.Mode,
): WebDavPickerArgsBuildResult {
    val relativePath = webDavRelativePathOrNull(
        baseUrl = rootUrl,
        resourceUrl = resourceUrl,
    ) ?: return WebDavPickerArgsBuildResult.Failure(
        WebDavSettingsState.Error.InvalidUrl,
    )
    val isKeePass = purpose == WebDavSettingsRoute.Purpose.KeePassDatabase
    return WebDavPickerArgsBuildResult.Success(
        rootUrl = rootUrl,
        args = WebDavPickerRoute.Args(
            rootUrl = rootUrl,
            username = username.trim(),
            password = password,
            mode = pickerMode,
            initialPath = if (isKeePass) {
                relativePath.substringBeforeLast('/', missingDelimiterValue = "")
            } else {
                relativePath
            },
            initialFileName = if (isKeePass) {
                relativePath.substringAfterLast('/')
            } else {
                ""
            },
        ),
    )
}

private fun buildWebDavPickerArgsFromEnteredUrl(
    url: String,
    username: String,
    password: String,
    purpose: WebDavSettingsRoute.Purpose,
    pickerMode: WebDavPickerRoute.Mode,
): WebDavPickerArgsBuildResult {
    val parsedFileUrl = if (purpose == WebDavSettingsRoute.Purpose.KeePassDatabase) {
        parseWebDavKeePassFileUrlOrNull(url)
    } else {
        null
    }
    val rootUrl = parsedFileUrl?.baseUrl
        ?: normalizeWebDavBrowseRootOrNull(url)
        ?: return WebDavPickerArgsBuildResult.Failure(
            WebDavSettingsState.Error.InvalidUrl,
        )
    return WebDavPickerArgsBuildResult.Success(
        rootUrl = rootUrl,
        args = WebDavPickerRoute.Args(
            rootUrl = rootUrl,
            username = username.trim(),
            password = password,
            mode = pickerMode,
            initialFileName = parsedFileUrl?.path.orEmpty(),
        ),
    )
}

internal fun webDavConnectionTestLocation(
    location: WebDavLocation,
    purpose: WebDavSettingsRoute.Purpose,
    keePassMode: WebDavSettingsRoute.KeePassMode,
): WebDavLocation =
    if (
        purpose == WebDavSettingsRoute.Purpose.KeePassDatabase &&
        keePassMode == WebDavSettingsRoute.KeePassMode.Create &&
        location is WebDavLocation.File
    ) {
        parseWebDavKeePassFileUrlOrNull(location.url)
            ?.let { parsed ->
                WebDavLocation.Collection(
                    url = parsed.baseUrl,
                    credentials = location.credentials,
                )
            }
            ?: location
    } else {
        location
    }

private fun normalizeWebDavBrowseRootOrNull(
    value: String,
): String? = try {
    val normalized = value.trim()
    require(
        normalized.startsWith("http://", ignoreCase = true) ||
                normalized.startsWith("https://", ignoreCase = true),
    )
    require(Url(normalized).host.isNotBlank())
    // The resolver strips the fragment, preserves the query,
    // and appends the trailing slash.
    resolveWebDavResourceUrl(
        baseUrl = normalized,
        path = "",
        collection = true,
    )
} catch (_: IllegalArgumentException) {
    null
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
    val inputError = validateWebDavFormInput(
        url = trimmedUrl,
        username = trimmedUsername,
        password = password,
    )
    return when {
        inputError != null -> WebDavSettingsBuildResult.Failure(inputError)

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
