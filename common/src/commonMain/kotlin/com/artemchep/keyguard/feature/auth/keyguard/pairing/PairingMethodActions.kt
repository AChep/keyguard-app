package com.artemchep.keyguard.feature.auth.keyguard.pairing

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material.icons.outlined.QrCodeScanner
import com.artemchep.keyguard.feature.localization.wrap
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.app_link_pairing_method_scan_text
import com.artemchep.keyguard.res.app_link_pairing_method_scan_title
import com.artemchep.keyguard.res.app_link_pairing_method_show_text
import com.artemchep.keyguard.res.app_link_pairing_method_show_title
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.ui.buildContextItems
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList

/**
 * Which of the two devices shows the code: a popup asks, so that the pairing starts
 * right away; [onPair] gets whether this device scans the code.
 */
fun pairingMethodActions(
    onPair: (scan: Boolean) -> Unit,
): ImmutableList<ContextItem> = buildContextItems {
    this += FlatItemAction(
        id = "scan",
        icon = Icons.Outlined.QrCodeScanner,
        title = Res.string.app_link_pairing_method_scan_title.wrap(),
        text = Res.string.app_link_pairing_method_scan_text.wrap(),
        onClick = { onPair(true) },
    )
    this += FlatItemAction(
        id = "show",
        icon = Icons.Outlined.QrCode,
        title = Res.string.app_link_pairing_method_show_title.wrap(),
        text = Res.string.app_link_pairing_method_show_text.wrap(),
        onClick = { onPair(false) },
    )
}.toPersistentList()
