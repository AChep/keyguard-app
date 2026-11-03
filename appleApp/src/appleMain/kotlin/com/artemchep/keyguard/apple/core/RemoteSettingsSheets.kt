package com.artemchep.keyguard.apple.core

import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.common.usecase.CheckS3Connection
import com.artemchep.keyguard.common.usecase.CheckWebDavConnection
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.s3.S3SettingsResult
import com.artemchep.keyguard.feature.s3.S3SettingsRoute
import com.artemchep.keyguard.feature.s3.S3SettingsState
import com.artemchep.keyguard.feature.s3.s3SettingsStateProducer
import com.artemchep.keyguard.feature.webdav.WebDavSettingsResult
import com.artemchep.keyguard.feature.webdav.WebDavSettingsRoute
import com.artemchep.keyguard.feature.webdav.WebDavSettingsState
import com.artemchep.keyguard.feature.webdav.webDavSettingsStateProducer
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/**
 * [purpose] is a [WebDavSettingsRoute.Purpose] name ("Collection" / "KeePassDatabase"). [errorKind] is a
 * [com.artemchep.keyguard.feature.webdav.WebDavSettingsState.Error] name ("UrlRequired" / "InvalidUrl" /
 * "FileUrlRequired" / "PasswordRequiresUsername"), or null.
 */
data class WebDavSettingsSnapshot(
    val id: String,
    val purpose: String,
    val url: String,
    val username: String,
    val password: String,
    val errorKind: String?,
    val isTestingConnection: Boolean,
)

/**
 * [purpose] is a [S3SettingsRoute.Purpose] name ("Prefix" / "KeePassDatabase"); [key] is the folder prefix, or the
 * object key of a KeePass database. [errorKind] is a [com.artemchep.keyguard.feature.s3.S3FormError] name
 * ("EndpointInvalid" / "BucketRequired" / "BucketInvalid" / "PrefixInvalid" / "KeyRequired" / "KeyInvalid" /
 * "KeyExtensionRequired" / "AccessKeyIdRequired" / "SecretAccessKeyRequired"), or null.
 */
data class S3SettingsSnapshot(
    val id: String,
    val purpose: String,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val key: String,
    val accessKeyId: String,
    val secretAccessKey: String,
    val pathStyle: Boolean,
    val errorKind: String?,
    val isTestingConnection: Boolean,
    val fieldErrors: List<S3SettingsFieldErrorSnapshot>,
    val validationRequest: Int,
    val validationField: String?,
)

data class S3SettingsFieldErrorSnapshot(
    val id: String,
    val kind: String,
)

/**
 * The WebDAV and S3 settings screens a shared producer opens, run as native sheets: claims the
 * route, runs the shared settings producer, and answers the route with its result. One sheet of
 * each kind at a time; Swift echoes the sheet id, so a dismissed sheet never edits its
 * replacement. Main-confined.
 */
@Suppress("TooManyFunctions") // Mirrors the settings producers' action contract for Swift.
class RemoteSettingsSheets internal constructor(
    private val ctx: CoreContext,
) {
    private val webDav = SettingsSheet<WebDavSettingsSnapshot, WebDavSettingsState, WebDavSettingsResult>(
        name = "webdav_settings",
    )

    private val s3 = SettingsSheet<S3SettingsSnapshot, S3SettingsState, S3SettingsResult>(
        name = "s3_settings",
    )

    /**
     * Claims a WebDAV or S3 settings route: runs its producer in [scope], with the connection
     * checks of [sessionKoin], and publishes the sheet to [onWebDavChange] or [onS3Change];
     * `null` once it closes. `false` for any other intent.
     */
    internal fun intercept(
        intent: NavigationIntent,
        scope: CoroutineScope,
        sessionKoin: Scope,
        onWebDavChange: (WebDavSettingsSnapshot?) -> Unit,
        onS3Change: (S3SettingsSnapshot?) -> Unit,
    ): Boolean {
        val webDavRoute = intent.resultRouteOrNull<WebDavSettingsRoute, WebDavSettingsResult>()
        val s3Route = intent.resultRouteOrNull<S3SettingsRoute, S3SettingsResult>()
        // Producers navigate from any thread; the sheets are main-confined.
        return when {
            webDavRoute != null -> {
                val (route, transmitter) = webDavRoute
                val checkWebDavConnection = sessionKoin.get<CheckWebDavConnection>()
                ctx.scope.launch {
                    if (scope.isActive) {
                        startWebDav(scope, route, transmitter, checkWebDavConnection, onWebDavChange)
                    }
                }
                true
            }

            s3Route != null -> {
                val (route, transmitter) = s3Route
                val checkS3Connection = sessionKoin.get<CheckS3Connection>()
                ctx.scope.launch {
                    if (scope.isActive) {
                        startS3(scope, route, transmitter, checkS3Connection, onS3Change)
                    }
                }
                true
            }

            else -> false
        }
    }

    /** Closes both sheets. */
    fun cancel() {
        webDav.cancel()
        s3.cancel()
    }

    /**
     * One sheet at a time, run as a child of whoever claimed its route.
     */
    private inner class SettingsSheet<Snapshot : Any, State : Any, Result>(
        private val name: String,
    ) {
        private var id: String? = null
        private var session: DetailSession<Snapshot, State>? = null
        private var onChange: ((Snapshot?) -> Unit)? = null

        fun start(
            scope: CoroutineScope,
            transmitter: RouteResultTransmitter<Result>,
            onChange: (Snapshot?) -> Unit,
            produce: suspend RememberStateFlowScope.(RouteResultTransmitter<Result>) -> Flow<State>,
            toSnapshot: (id: String, state: State) -> Snapshot,
        ) {
            cancel()
            val id = Uuid.random().toString()
            val child = DetailSession<Snapshot, State>()
            this.id = id
            this.session = child
            this.onChange = onChange
            val onSaved = child.gated<Result> { result ->
                transmitter(result)
                cancel()
            }
            val wrappedTransmitter = object : RouteResultTransmitter<Result> {
                override fun invoke(p1: Result) {
                    ctx.scope.launch { onSaved(p1) }
                }
            }
            child.observe(onChange) { publish ->
                KeyguardCancellable(
                    scope.launch {
                        ctx.koin.newHeadlessStateFlowScope(name, this)
                            .produce(wrappedTransmitter)
                            .throttleLatest()
                            .map { state -> toSnapshot(id, state) to state }
                            .collectOnMain { (snapshot, state) -> publish(snapshot, state) }
                    },
                )
            }
        }

        fun withActions(sessionId: String, block: (State) -> Unit) {
            session?.takeIf { id == sessionId }?.withActions(block)
        }

        fun cancel() {
            val session = session
                ?: return
            session.close()
            this.session = null
            id = null
            onChange?.invoke(null)
            onChange = null
        }
    }

    private fun startWebDav(
        scope: CoroutineScope,
        route: WebDavSettingsRoute,
        transmitter: RouteResultTransmitter<WebDavSettingsResult>,
        checkWebDavConnection: CheckWebDavConnection,
        onChange: (WebDavSettingsSnapshot?) -> Unit,
    ) = webDav.start(
        scope = scope,
        transmitter = transmitter,
        onChange = onChange,
        produce = { wrappedTransmitter ->
            webDavSettingsStateProducer(
                route = route,
                transmitter = wrappedTransmitter,
                checkWebDavConnection = checkWebDavConnection,
            )
        },
    ) { id, state ->
        WebDavSettingsSnapshot(
            id = id,
            purpose = route.args.purpose.name,
            url = state.url.value,
            username = state.username.value,
            password = state.password.value,
            errorKind = state.error?.name,
            isTestingConnection = state.isTestingConnection,
        )
    }

    private fun withWebDav(sessionId: String, block: (WebDavSettingsState) -> Unit) =
        webDav.withActions(sessionId, block)

    fun setWebDavField(sessionId: String, id: String, text: String) = withWebDav(sessionId) { state ->
        when (id) {
            // The URL is written through the producer's own setter, which also
            // invalidates the remembered WebDAV browse root.
            "url" -> state.onUrlChange(text)
            "username" -> state.username.value = text
            "password" -> state.password.value = text
        }
    }

    fun submitWebDavSettings(sessionId: String) = withWebDav(sessionId) { it.onSave() }

    fun testWebDavConnection(sessionId: String) = withWebDav(sessionId) { it.onTestConnection() }

    fun cancelWebDavSettings() = webDav.cancel()

    private fun startS3(
        scope: CoroutineScope,
        route: S3SettingsRoute,
        transmitter: RouteResultTransmitter<S3SettingsResult>,
        checkS3Connection: CheckS3Connection,
        onChange: (S3SettingsSnapshot?) -> Unit,
    ) = s3.start(
        scope = scope,
        transmitter = transmitter,
        onChange = onChange,
        produce = { wrappedTransmitter ->
            s3SettingsStateProducer(
                route = route,
                transmitter = wrappedTransmitter,
                checkS3Connection = checkS3Connection,
            )
        },
    ) { id, state ->
        S3SettingsSnapshot(
            id = id,
            purpose = route.args.purpose.name,
            endpoint = state.endpoint.value,
            region = state.region.value,
            bucket = state.bucket.value,
            key = state.path.value,
            accessKeyId = state.accessKeyId.value,
            secretAccessKey = state.secretAccessKey.value,
            pathStyle = state.pathStyle.value,
            errorKind = state.error?.name,
            isTestingConnection = state.isTestingConnection,
            fieldErrors = state.validation.errors.map { S3SettingsFieldErrorSnapshot(it.field, it.name) },
            validationRequest = state.validation.request,
            validationField = state.validation.focusField,
        )
    }

    private fun withS3(sessionId: String, block: (S3SettingsState) -> Unit) =
        s3.withActions(sessionId, block)

    fun setS3Field(sessionId: String, id: String, text: String) = withS3(sessionId) { state ->
        val field = when (id) {
            "endpoint" -> state.endpoint
            "region" -> state.region
            "bucket" -> state.bucket
            "key" -> state.path
            "accessKeyId" -> state.accessKeyId
            "secretAccessKey" -> state.secretAccessKey
            else -> return@withS3
        }
        if (field.value != text) {
            field.value = text
            state.onFieldEdited(id)
        }
    }

    fun blurS3Field(sessionId: String, id: String) = withS3(sessionId) { it.onFieldBlurred(id) }

    fun setS3PathStyle(sessionId: String, value: Boolean) = withS3(sessionId) { state ->
        if (state.pathStyle.value != value) {
            state.pathStyle.value = value
            state.onFieldEdited("bucket")
        }
    }

    fun submitS3Settings(sessionId: String) = withS3(sessionId) { it.onSave() }

    fun testS3Connection(sessionId: String) = withS3(sessionId) { it.onTestConnection() }

    fun cancelS3Settings() = s3.cancel()
}
