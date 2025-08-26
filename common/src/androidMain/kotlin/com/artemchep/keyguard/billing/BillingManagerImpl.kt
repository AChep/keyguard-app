package com.artemchep.keyguard.billing

import android.content.Context
import arrow.core.partially1
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.artemchep.keyguard.common.io.throttle
import com.artemchep.keyguard.common.model.RichResult
import com.artemchep.keyguard.common.util.flow.EventFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext

private const val RECONNECT_DELAY = 1000L * 10L // 10s

/**
 * @author Artem Chepurnyi
 */
class BillingManagerImpl(
    private val context: Context,
) : BillingManager {
    companion object {
        private const val TAG = "BillingManager"

        private const val SHARING_TIMEOUT_MS = 5000L
    }

    override val billingConnectionFlow: Flow<BillingConnection> = flow {
        coroutineScope {
            val connection = launchIn(this)
            emit(connection)
        }
    }.shareIn(GlobalScope, SharingStarted.WhileSubscribed(SHARING_TIMEOUT_MS), replay = 1)

    private fun launchIn(scope: CoroutineScope): BillingConnection {
        val dataChangedEventFlow = EventFlow<Unit>()
        val billingClient = BillingClient
            .newBuilder(context)
            .enableAutoServiceReconnection()
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .setListener { _, _ ->
                dataChangedEventFlow.emit(Unit)
            }
            .build()
        val liveFlow = MutableStateFlow<RichResult<BillingClient>>(RichResult.Loading())

        val job = scope.launch(Dispatchers.Main.immediate) {
            val requestConnectSink = EventFlow<Unit>()
            val requestConnect = requestConnectSink::emit
                .partially1(Unit)

            val listener = object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    val event: RichResult<BillingClient> =
                        when (val code = billingResult.responseCode) {
                            BillingClient.BillingResponseCode.OK -> {
                                RichResult.Success(billingClient)
                            }

                            BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                            BillingClient.BillingResponseCode.ERROR,
                            -> {
                                when (code) {
                                    BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
                                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
                                    -> requestConnect()
                                }
                                RichResult.Failure(BillingClientApiException(code))
                            }

                            else -> return
                        }
                    liveFlow.value = event
                }

                override fun onBillingServiceDisconnected() {
                    liveFlow.value = RichResult.Failure(BillingClientDisconnectedException())
                    requestConnect()
                }
            }

            val tryConnect = {
                // We're trying to connect, notify the user
                // about it...
                liveFlow.value = RichResult.Loading()
                // ... and try to connect.
                billingClient.startConnection(listener)
            }

            val flow = requestConnectSink
                .throttle(RECONNECT_DELAY, sendLastEvent = true)
                .onEach { tryConnect() }
                .onStart { tryConnect() }
            try {
                flow.collect()
            } finally {
                withContext(NonCancellable + Dispatchers.Main) {
                    billingClient.endConnection()
                }
            }
        }

        return BillingConnectionImpl(
            coroutineScope = scope + job,
            dataChangedLiveFlow = dataChangedEventFlow,
            clientLiveFlow = liveFlow,
        )
    }
}
