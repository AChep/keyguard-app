package com.artemchep.keyguard.feature.s3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import com.artemchep.keyguard.common.io.effectTap
import com.artemchep.keyguard.common.model.S3Location
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.common.usecase.CheckS3Connection
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.remotepicker.RemotePickerMode
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.remote_connection_test_success
import com.artemchep.keyguard.util.s3.isValidS3ObjectKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.koin.compose.currentKoinScope

@Composable
fun produceS3SettingsState(
    route: S3SettingsRoute,
    transmitter: RouteResultTransmitter<S3SettingsResult>,
): S3SettingsState = with(currentKoinScope()) {
    produceS3SettingsState(
        route = route,
        transmitter = transmitter,
        checkS3Connection = get(),
    )
}

@Composable
fun produceS3SettingsState(
    route: S3SettingsRoute,
    transmitter: RouteResultTransmitter<S3SettingsResult>,
    checkS3Connection: CheckS3Connection,
): S3SettingsState = produceScreenState(
    key = "s3_settings",
    initial = S3SettingsState(
        endpoint = mutableStateOf(route.args.endpoint),
        region = mutableStateOf(route.args.region),
        bucket = mutableStateOf(route.args.bucket),
        path = mutableStateOf(route.args.path),
        accessKeyId = mutableStateOf(route.args.accessKeyId),
        secretAccessKey = mutableStateOf(route.args.secretAccessKey),
        pathStyle = mutableStateOf(route.args.pathStyle),
        error = null,
        isTestingConnection = false,
        onBrowse = {},
        onSave = {},
        onTestConnection = {},
    ),
    args = arrayOf(
        route,
        checkS3Connection,
    ),
) {
    s3SettingsStateProducer(
        route = route,
        transmitter = transmitter,
        checkS3Connection = checkS3Connection,
    )
}

suspend fun RememberStateFlowScope.s3SettingsStateProducer(
    route: S3SettingsRoute,
    transmitter: RouteResultTransmitter<S3SettingsResult>,
    checkS3Connection: CheckS3Connection,
): Flow<S3SettingsState> {
    val testExecutor = screenExecutor()
    val validationSink = MutableStateFlow(S3FormValidation())
    val endpointState = mutableStateOf(route.args.endpoint)
    val regionState = mutableStateOf(route.args.region)
    val bucketState = mutableStateOf(route.args.bucket)
    val pathState = mutableStateOf(route.args.path)
    val accessKeyIdState = mutableStateOf(route.args.accessKeyId)
    val secretAccessKeyState = mutableStateOf(route.args.secretAccessKey)
    val pathStyleState = mutableStateOf(route.args.pathStyle)

    fun input() = S3FormInput(
        endpoint = endpointState.value,
        region = regionState.value,
        bucket = bucketState.value,
        path = pathState.value,
        accessKeyId = accessKeyIdState.value,
        secretAccessKey = secretAccessKeyState.value,
        pathStyle = pathStyleState.value,
    )

    fun buildLocationOrReportError(): S3Location? {
        val input = input()
        val errors = s3FormErrors(input, route.args.purpose)
        validationSink.value = validationSink.value.submit(errors)
        return if (errors.isEmpty()) buildS3Location(input, route.args.purpose) else null
    }

    fun onSave() {
        val location = buildLocationOrReportError()
            ?: return
        transmitter(S3SettingsResult(location))
        navigatePopSelf()
    }

    fun onTestConnection() {
        val location = buildLocationOrReportError()
            ?: return
        val testLocation = s3ConnectionTestLocation(
            location = location,
            keePassMode = route.args.keePassMode,
        )
        val io = checkS3Connection(testLocation).effectTap {
            message(
                ToastMessage(
                    title = translate(Res.string.remote_connection_test_success, "S3"),
                    type = ToastMessage.Type.SUCCESS,
                ),
            )
        }
        testExecutor.execute(io)
    }

    fun onBrowse() {
        val input = input()
        val errors = s3ConnectionErrors(input)
        validationSink.value = validationSink.value.submit(errors)
        if (errors.isNotEmpty()) {
            return
        }
        val pickerRoute = registerRouteResultReceiver(
            route = S3PickerRoute(
                args = buildS3PickerArgs(
                    input = input,
                    purpose = route.args.purpose,
                    keePassMode = route.args.keePassMode,
                ),
            ),
        ) { pickerResult ->
            pathState.value = pickerResult.key
            validationSink.value = S3FormValidation(request = validationSink.value.request)
        }
        navigate(
            NavigationIntent.NavigateToRoute(pickerRoute),
        )
    }

    return combine(
        validationSink,
        testExecutor.isExecutingFlow,
    ) { validation, isTestingConnection ->
        S3SettingsState(
            endpoint = endpointState,
            region = regionState,
            bucket = bucketState,
            path = pathState,
            accessKeyId = accessKeyIdState,
            secretAccessKey = secretAccessKeyState,
            pathStyle = pathStyleState,
            error = validation.errors.firstOrNull(),
            isTestingConnection = isTestingConnection,
            onBrowse = ::onBrowse,
            onSave = ::onSave,
            onTestConnection = ::onTestConnection,
            validation = validation,
            onFieldEdited = { field ->
                validationSink.value = validationSink.value.edit(field, s3FormErrors(input(), route.args.purpose))
            },
            onFieldBlurred = { field ->
                validationSink.value = validationSink.value.blur(field, s3FormErrors(input(), route.args.purpose))
            },
        )
    }
}

/** Builds picker arguments from input that passed [validateS3Connection]. */
internal fun buildS3PickerArgs(
    input: S3FormInput,
    purpose: S3SettingsRoute.Purpose,
    keePassMode: S3SettingsRoute.KeePassMode,
): S3PickerRoute.Args {
    val path = normalizeS3FormPath(input.path, purpose)
    val (initialPath, initialFileName) = when (purpose) {
        // The picker path has no trailing slash.
        S3SettingsRoute.Purpose.Prefix -> path.removeSuffix("/") to ""
        S3SettingsRoute.Purpose.KeePassDatabase -> if (isValidS3ObjectKey(path)) {
            path.substringBeforeLast('/', missingDelimiterValue = "") to path.substringAfterLast('/')
        } else {
            "" to ""
        }
    }
    return S3PickerRoute.Args(
        bucket = input.toS3Bucket(),
        accessKey = input.toS3AccessKey(),
        mode = when (purpose) {
            S3SettingsRoute.Purpose.Prefix -> RemotePickerMode.SelectFolder
            S3SettingsRoute.Purpose.KeePassDatabase -> when (keePassMode) {
                S3SettingsRoute.KeePassMode.Open -> RemotePickerMode.OpenKeePassDatabase
                S3SettingsRoute.KeePassMode.Create -> RemotePickerMode.CreateKeePassDatabase
            }
        },
        initialPath = initialPath,
        initialFileName = initialFileName,
    )
}

/**
 * A database that is about to be created does not exist yet, so the
 * connection test writes a probe next to it instead of reading it.
 */
internal fun s3ConnectionTestLocation(
    location: S3Location,
    keePassMode: S3SettingsRoute.KeePassMode,
): S3Location = if (location is S3Location.Object && keePassMode == S3SettingsRoute.KeePassMode.Create) {
    val parent = location.key.substringBeforeLast('/', missingDelimiterValue = "")
    S3Location.Prefix(
        bucket = location.bucket,
        accessKey = location.accessKey,
        prefix = if (parent.isEmpty()) "" else "$parent/",
    )
} else {
    location
}
