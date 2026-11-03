package com.artemchep.keyguard.feature.auth.certificate

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.Route
import com.artemchep.keyguard.feature.navigation.RouteDescriptor

data class AccountClientCertificateRoute(val accountId: String) : Route {
    override val descriptor get() = RouteDescriptor.AccountClientCertificate(accountId)

    @Composable
    override fun Content() = AccountClientCertificateScreen(accountId)
}
