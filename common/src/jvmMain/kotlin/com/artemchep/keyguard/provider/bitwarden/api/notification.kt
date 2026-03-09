package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.messagepack.MessagePackHubProtocol
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.api.builder.notifications
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.Subscription
import com.microsoft.signalr.TransportEnum
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.CompletableSource
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NotificationsHubDisconnectedException(
    cause: Throwable,
) : RuntimeException(cause)

suspend fun notificationsHub(
    user: BitwardenToken,
    onMessage: (Any) -> Unit,
    onHeartbeat: () -> Unit,
) {
    val a = user.env.back().notifications.hub
    val hubConnection = HubConnectionBuilder.create(a)
        .withAccessTokenProvider(Single.just(user.token!!.accessToken))
        .shouldSkipNegotiate(true)
        .withHubProtocol(MessagePackHubProtocol())
        .withTransport(TransportEnum.WEBSOCKETS)
        .build()
    notificationsHub(
        connection = SignalRNotificationsHubConnection(hubConnection),
        onMessage = onMessage,
        onHeartbeat = onHeartbeat,
    )
}

internal suspend fun notificationsHub(
    connection: NotificationsHubConnection,
    onMessage: (Any) -> Unit,
    onHeartbeat: () -> Unit,
) {
    val receiveMessageSubscription = connection.onReceiveMessage {
        logNotificationEvent("receive message")
        onMessage(it)
    }
    val heartbeatSubscription = connection.onHeartbeat {
        logNotificationEvent("heartbeat")
        onHeartbeat()
    }

    val closeSignal = CompletableDeferred<Unit>()
    connection.onClosed { error ->
        if (error != null) {
            closeSignal.completeExceptionally(error)
            return@onClosed
        }

        closeSignal.complete(Unit)
    }

    try {
        connection.start()
        closeSignal.await()
    } finally {
        withContext(NonCancellable) {
            runCatching {
                connection.stop()
            }
        }
        runCatching { receiveMessageSubscription.unsubscribe() }
        runCatching { heartbeatSubscription.unsubscribe() }
    }
}

internal interface NotificationsHubConnection {
    fun onReceiveMessage(block: (Any) -> Unit): NotificationsHubSubscription
    fun onHeartbeat(block: () -> Unit): NotificationsHubSubscription
    fun onClosed(block: (Throwable?) -> Unit)

    suspend fun start()

    suspend fun stop()
}

internal fun interface NotificationsHubSubscription {
    fun unsubscribe()
}

private class SignalRNotificationsHubConnection(
    private val hubConnection: HubConnection,
) : NotificationsHubConnection {
    override fun onReceiveMessage(
        block: (Any) -> Unit,
    ): NotificationsHubSubscription =
        hubConnection
            .on(
                "ReceiveMessage",
                block,
                Any::class.java,
            )
            .asNotificationsHubSubscription()

    override fun onHeartbeat(
        block: () -> Unit,
    ): NotificationsHubSubscription = hubConnection
        .on("Heartbeat", block)
        .asNotificationsHubSubscription()

    override fun onClosed(block: (Throwable?) -> Unit) {
        hubConnection.onClosed(block)
    }

    override suspend fun start() {
        hubConnection.start().await()
    }

    override suspend fun stop() {
        hubConnection.stop().await()
    }
}

private fun Subscription.asNotificationsHubSubscription(
): NotificationsHubSubscription = NotificationsHubSubscription {
    unsubscribe()
}

/**
 * Awaits for completion of this completable without blocking the thread.
 * Returns `Unit`, or throws the corresponding exception if this completable produces an error.
 *
 * This suspending function is cancellable. If the [Job] of the invoking coroutine is cancelled or completed while this
 * suspending function is suspended, this function immediately resumes with [CancellationException] and disposes of its
 * subscription.
 */
private suspend fun CompletableSource.await(): Unit = suspendCancellableCoroutine { cont ->
    subscribe(
        object : CompletableObserver {
            override fun onSubscribe(d: Disposable) {
                cont.disposeOnCancellation(d)
            }

            override fun onComplete() {
                cont.resume(Unit)
            }

            override fun onError(e: Throwable) {
                cont.resumeWithException(e)
            }
        },
    )
}

private fun CancellableContinuation<*>.disposeOnCancellation(d: Disposable) =
    invokeOnCancellation { d.dispose() }

private fun logNotificationEvent(message: String) {
    recordLog("$message @ notifications")
}
