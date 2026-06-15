package com.artemchep.keyguard.wear.feature.changepassword

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearChangePasswordRoute : Route {
    @Composable
    override fun Content() {
        WearChangePasswordScreen()
    }
}
