package com.artemchep.keyguard.provider.bitwarden.api

import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken
import com.artemchep.keyguard.messagepack.MessagePackHubProtocol
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.provider.bitwarden.api.builder.notifications
import com.microsoft.signalr.HubConnectionBuilder
import com.microsoft.signalr.TransportEnum
import io.reactivex.rxjava3.core.CompletableObserver
import io.reactivex.rxjava3.core.CompletableSource
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun notificationsHub(
    user: BitwardenToken,
    onMessage: (Any) -> Unit,
) {
    val a = user.env.back().notifications.hub
    val hubConnection = HubConnectionBuilder.create(a) //
        .withAccessTokenProvider(Single.just(user.token!!.accessToken))
        .shouldSkipNegotiate(true)
        .withHubProtocol(MessagePackHubProtocol())
        .withTransport(TransportEnum.WEBSOCKETS)
        .build()
    hubConnection.on(
        "ReceiveMessage",
        {
            recordLog("message @ notifications")
            onMessage(it)
        },
        Any::class.java,
    )
    hubConnection.on("Heartbeat") {
    }
    hubConnection
        .start()
        .await()
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
