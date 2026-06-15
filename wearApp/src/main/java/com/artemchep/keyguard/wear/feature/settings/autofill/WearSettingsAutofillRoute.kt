package com.artemchep.keyguard.wear.feature.settings.autofill

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.artemchep.keyguard.feature.navigation.Route

@Stable
object WearSettingsAutofillRoute : Route {
    @Composable
    override fun Content() {
        WearSettingsAutofillScreen()
    }
}
