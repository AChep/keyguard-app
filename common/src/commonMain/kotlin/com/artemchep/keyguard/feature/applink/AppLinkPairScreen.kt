package com.artemchep.keyguard.feature.applink

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.feature.auth.keyguard.KeyguardPairContent
import com.artemchep.keyguard.feature.auth.keyguard.pairing.PairingMode
import com.artemchep.keyguard.feature.navigation.LocalNavigationController
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_link_another_device
import com.artemchep.keyguard.res.app_link_link_device_title
import com.artemchep.keyguard.res.app_link_premium_note
import org.jetbrains.compose.resources.stringResource

/**
 * Pairs this app with another one: this device [scan]s the code of the other one, or
 * shows its own. Once the devices are linked it celebrates, and closes when the user is
 * done.
 */
@Composable
fun AppLinkPairScreen(
    scan: Boolean,
) {
    val mode = if (scan) PairingMode.SCAN_CODE else PairingMode.SHOW_CODE
    val loadableState = produceAppLinkPairScreenState(mode)
    val controller by rememberUpdatedState(LocalNavigationController.current)
    KeyguardPairContent(
        loadableState = loadableState,
        title = stringResource(Res.string.app_link_link_device_title),
        premiumNote = stringResource(Res.string.app_link_premium_note),
        addAnotherTitle = stringResource(Res.string.app_link_link_another_device),
        onDone = {
            controller.queue(NavigationIntent.Pop)
        },
    )
}
