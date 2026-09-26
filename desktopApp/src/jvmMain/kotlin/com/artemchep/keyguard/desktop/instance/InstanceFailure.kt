package com.artemchep.keyguard.desktop.instance

import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.util.instance.InstanceException
import com.artemchep.keyguard.util.instance.InstanceFailureKind
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** Visible before DI/Compose exists. Returns true if the running app should quit. */
internal fun showInstanceFailure(error: Exception, running: Boolean = false): Boolean {
    val details = instanceFailureDetails(error)
    System.err.println("nativeInstance failed: $details")
    val (title, message, options) = runBlocking {
        val explanation = getString(instanceFailureMessage((error as? InstanceException)?.kind))
        Triple(
            getString(Res.string.instance_service_error_title),
            if (running) {
                getString(Res.string.instance_service_stopped_text) + "\n\n" + explanation
            } else {
                explanation
            },
            if (running) {
                arrayOf(getString(Res.string.quit), getString(Res.string.close))
            } else {
                arrayOf(getString(Res.string.close))
            },
        )
    }
    var quit = false
    val show = Runnable {
        val messageArea = JTextArea(message, if (running) 7 else 4, 56).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        // Selectable diagnostics remain accessible even when the packaged launcher
        // has no stdout/stderr. Do not display arbitrary exception messages or causes.
        val detailsArea = JTextArea(details, 3, 56).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        val choice = JOptionPane.showOptionDialog(
            null,
            arrayOf(JScrollPane(messageArea), JScrollPane(detailsArea)),
            title,
            JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options.last(),
        )
        quit = running && choice == 0
    }
    if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)
    return quit
}

internal fun instanceFailureMessage(kind: InstanceFailureKind?): StringResource = when (kind) {
    InstanceFailureKind.PERMISSION -> Res.string.instance_service_permission_text
    InstanceFailureKind.TIMEOUT -> Res.string.instance_service_timeout_text
    InstanceFailureKind.UNAVAILABLE -> Res.string.instance_service_unavailable_text
    InstanceFailureKind.INVALID_ARGUMENT, InstanceFailureKind.PROTOCOL -> Res.string.instance_service_configuration_text
    InstanceFailureKind.IO, InstanceFailureKind.INVALID_HANDLE, InstanceFailureKind.INTERNAL, null ->
        Res.string.instance_service_error_text
}

internal fun instanceFailureDetails(error: Exception): String = when (error) {
    is InstanceException -> error.diagnostic ?: "kind=${error.kind}"
    else -> "kind=INTERNAL exception=${error.javaClass.simpleName}"
}
