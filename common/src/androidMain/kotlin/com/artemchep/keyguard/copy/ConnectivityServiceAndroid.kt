package com.artemchep.keyguard.copy

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.artemchep.keyguard.common.service.connectivity.ConnectivityService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import org.kodein.di.DirectDI
import org.kodein.di.instance

class ConnectivityServiceAndroid(
    private val context: Context,
) : ConnectivityService {
    constructor(
        directDI: DirectDI,
    ) : this(
        context = directDI.instance<Application>(),
    )

    override val availableFlow: Flow<Unit> = channelFlow<Unit> {
        // Return the callback immediately if the internet is
        // available.
        if (isInternetAvailable()) {
            send(Unit)
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        val networkRequest: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val connectivityManager = context.getSystemService<ConnectivityManager>()
        connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)

        awaitClose {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        }
    }

    override fun isInternetAvailable(): Boolean {
        val connectivityManager = context.getSystemService<ConnectivityManager>()
            ?: return false
        val networkCapabilities = connectivityManager.activeNetwork
            ?: return false
        val actNw = connectivityManager.getNetworkCapabilities(networkCapabilities)
            ?: return false
        return when {
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            actNw.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }
}
