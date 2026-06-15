package com.artemchep.keyguard.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.companion.CompanionAuthHeader
import com.artemchep.keyguard.android.companion.CompanionBitwardenLoginRoute
import com.artemchep.keyguard.android.companion.CompanionKeePassLoginRoute
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthBridgeAndroid
import com.artemchep.keyguard.feature.auth.companion.CompanionAuthProvider
import com.artemchep.keyguard.feature.home.HomeLayout
import com.artemchep.keyguard.feature.home.LocalHomeLayout
import com.artemchep.keyguard.feature.navigation.NavigationNode
import com.artemchep.keyguard.feature.navigation.NavigationRouter
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.registerRouteResultReceiver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.kodein.di.instance

class CompanionAuthActivity : BaseActivity() {
    companion object {
        private const val QUERY_REQUEST_ID = "requestId"
        private const val QUERY_PROVIDER = "provider"
        private const val HOST = "wear-companion-auth"

        fun getIntent(
            context: Context,
            requestId: String,
            provider: CompanionAuthProvider,
        ): Intent {
            val uri = Uri.Builder()
                .scheme("keyguard")
                .authority(HOST)
                .appendQueryParameter(QUERY_REQUEST_ID, requestId)
                .appendQueryParameter(QUERY_PROVIDER, provider.name)
                .build()
            return Intent(Intent.ACTION_VIEW, uri)
                .setClass(context, CompanionAuthActivity::class.java)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }

    private val companionAuthBridge by instance<CompanionAuthBridgeAndroid>()
    private val companionAuthEvents = Channel<CompanionAuthEvent>(Channel.BUFFERED)

    private var request by mutableStateOf<Request?>(null)
    private var completedRequestId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            for (event in companionAuthEvents) {
                when (event) {
                    is CompanionAuthEvent.ApplyIntent -> applyIntent(event.intent)
                    is CompanionAuthEvent.CompleteRequest -> completeRequest(event.request)
                }
            }
        }
        sendEvent(CompanionAuthEvent.ApplyIntent(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sendEvent(CompanionAuthEvent.ApplyIntent(intent))
    }

    override fun onDestroy() {
        companionAuthEvents.cancel()
        if (!isChangingConfigurations) {
            request
                ?.takeUnless { it.requestId == completedRequestId }
                ?.let(::notifyCancelled)
        }
        super.onDestroy()
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalHomeLayout provides HomeLayout.Vertical,
        ) {
            val route = remember {
                CompanionAppRoute()
            }
            NavigationNode(
                id = "CompanionAuth:Main",
                route = route,
            )
        }
    }

    private inner class CompanionAppRoute : Route {
        @Composable
        override fun Content() {
            CompanionAppScreen()
        }
    }

    @Composable
    private fun CompanionAppScreen() {
        val request = request
            ?: return
        val route = remember(request) {
            when (request.provider) {
                CompanionAuthProvider.BITWARDEN -> {
                    registerRouteResultReceiver(
                        CompanionBitwardenLoginRoute(request),
                    ) {
                        onRequestCompleted(request)
                    }
                }

                CompanionAuthProvider.KEEPASS -> {
                    registerRouteResultReceiver(
                        CompanionKeePassLoginRoute(request),
                    ) {
                        onRequestCompleted(request)
                    }
                }
            }
        }
        ExtensionScaffold(
            header = {
                CompanionAuthHeader()
            },
        ) {
            NavigationRouter(
                id = "CompanionAuth:${request.requestId}",
                initial = route,
            ) {
                NavigationNode(it)
            }
        }
    }

    private fun sendEvent(
        event: CompanionAuthEvent,
    ) {
        companionAuthEvents.trySend(event)
    }

    private suspend fun parseRequest(
        uri: Uri?,
    ): Request? = resolveCompanionAuthLaunchRequest(
        currentRequest = request,
        requestId = uri?.getQueryParameter(QUERY_REQUEST_ID),
        rawProvider = uri?.getQueryParameter(QUERY_PROVIDER),
    ) { requestId, provider ->
        companionAuthBridge.getLaunchRequest(
            requestId = requestId,
            provider = provider,
        )
    }

    private suspend fun applyIntent(
        intent: Intent?,
    ) {
        val update = resolveCompanionAuthRequestUpdate(
            currentRequest = request,
            completedRequestId = completedRequestId,
            incomingRequest = parseRequest(intent?.data),
        )
        update.requestToCancel?.let(::notifyCancelled)
        request = update.request
        completedRequestId = update.completedRequestId
        if (update.shouldFinish) {
            finish()
        }
    }

    private fun onRequestCompleted(
        request: Request,
    ) {
        sendEvent(CompanionAuthEvent.CompleteRequest(request))
    }

    private fun completeRequest(
        request: Request,
    ) {
        if (this.request != request) {
            return
        }
        completedRequestId = request.requestId
        finish()
    }

    private fun notifyCancelled(
        request: Request,
    ) {
        companionAuthBridge.notifyCancelledFromPhone(
            requestId = request.requestId,
            provider = request.provider,
        )
    }

    private sealed interface CompanionAuthEvent {
        data class ApplyIntent(
            val intent: Intent?,
        ) : CompanionAuthEvent

        data class CompleteRequest(
            val request: Request,
        ) : CompanionAuthEvent
    }

    data class Request(
        val requestId: String,
        val provider: CompanionAuthProvider,
    )
}

internal data class CompanionAuthRequestUpdate(
    val request: CompanionAuthActivity.Request?,
    val completedRequestId: String?,
    val requestToCancel: CompanionAuthActivity.Request?,
    val shouldFinish: Boolean,
)

internal fun parseCompanionAuthRequest(
    requestId: String?,
    rawProvider: String?,
): CompanionAuthActivity.Request? {
    val normalizedRequestId = com.artemchep.keyguard.feature.auth.companion
        .canonicalCompanionAuthRequestIdOrNull(requestId)
        ?: return null
    val provider = rawProvider
        ?.let { key ->
            CompanionAuthProvider.entries.firstOrNull { it.name == key }
        }
        ?: return null
    return CompanionAuthActivity.Request(
        requestId = normalizedRequestId,
        provider = provider,
    )
}

internal suspend fun resolveCompanionAuthLaunchRequest(
    currentRequest: CompanionAuthActivity.Request?,
    requestId: String?,
    rawProvider: String?,
    launchRequestResolver: suspend (
        requestId: String,
        provider: CompanionAuthProvider,
    ) -> CompanionAuthActivity.Request?,
): CompanionAuthActivity.Request? {
    val parsedRequest = parseCompanionAuthRequest(
        requestId = requestId,
        rawProvider = rawProvider,
    ) ?: return null
    if (parsedRequest == currentRequest) {
        return currentRequest
    }
    return launchRequestResolver(
        parsedRequest.requestId,
        parsedRequest.provider,
    )
}

internal fun resolveCompanionAuthRequestUpdate(
    currentRequest: CompanionAuthActivity.Request?,
    completedRequestId: String?,
    incomingRequest: CompanionAuthActivity.Request?,
): CompanionAuthRequestUpdate {
    val activeRequestToCancel = currentRequest
        ?.takeUnless { it.requestId == completedRequestId }
    return when {
        incomingRequest == null -> CompanionAuthRequestUpdate(
            request = null,
            completedRequestId = null,
            requestToCancel = activeRequestToCancel,
            shouldFinish = true,
        )

        currentRequest == null -> CompanionAuthRequestUpdate(
            request = incomingRequest,
            completedRequestId = null,
            requestToCancel = null,
            shouldFinish = false,
        )

        incomingRequest == currentRequest -> CompanionAuthRequestUpdate(
            request = currentRequest,
            completedRequestId = completedRequestId,
            requestToCancel = null,
            shouldFinish = false,
        )

        else -> CompanionAuthRequestUpdate(
            request = incomingRequest,
            completedRequestId = null,
            requestToCancel = activeRequestToCancel,
            shouldFinish = false,
        )
    }
}
