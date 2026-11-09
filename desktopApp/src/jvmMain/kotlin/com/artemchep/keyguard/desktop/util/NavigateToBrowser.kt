package com.artemchep.keyguard.desktop.util

import java.awt.Desktop
import java.net.URI

fun navigateToBrowser(
    uri: String,
) {
    openUri(URI(uri), Desktop.Action.BROWSE) { browse(it) }
}
