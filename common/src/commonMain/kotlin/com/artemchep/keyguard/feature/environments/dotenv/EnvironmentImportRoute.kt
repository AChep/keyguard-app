package com.artemchep.keyguard.feature.environments.dotenv

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.feature.navigation.DialogRouteForResult
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter

/**
 * Asks for `.env` content, pasted or read from a file, that
 * replaces the variables of an environment. The result is the
 * `.env` text; it is never persisted.
 *
 * @param fileUri A file to read the content from, for
 *   example one that was dropped onto the editor.
 */
data class EnvironmentImportRoute(
    val fileUri: String? = null,
) : DialogRouteForResult<String> {
    @Composable
    override fun Content(
        transmitter: RouteResultTransmitter<String>,
    ) {
        EnvironmentImportScreen(
            fileUri = fileUri,
            transmitter = transmitter,
        )
    }
}
