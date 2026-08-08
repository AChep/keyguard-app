package com.artemchep.keyguard.ipctestclient.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.openintents.openpgp.util.OpenPgpApi
import org.openintents.ssh.authentication.SshAuthenticationApi

/**
 * Resolution of the Keyguard provider components, kept separate from binding so
 * discovery can be asserted on its own.
 */
object IpcProviders {
    const val KEYGUARD_PACKAGE_PREFIX = "com.artemchep.keyguard"

    /**
     * The OpenPGP API v1 bind action. Keyguard must never publish it; the v1
     * interface has no output pipe and no per-request caller attribution.
     */
    const val LEGACY_OPENPGP_SERVICE_ACTION = "org.openintents.openpgp.IOpenPgpService"

    const val OPENPGP_SERVICE_ACTION = OpenPgpApi.SERVICE_INTENT_2
    const val SSH_SERVICE_ACTION = SshAuthenticationApi.SERVICE_INTENT

    data class Provider(
        val component: ComponentName,
        val label: String,
    )

    /** Every service that answers [action], Keyguard or not. */
    @Suppress("DEPRECATION")
    fun resolveAll(
        context: Context,
        action: String,
    ): List<Provider> = context.packageManager
        .queryIntentServices(Intent(action), 0)
        .map { info ->
            Provider(
                component = ComponentName(
                    info.serviceInfo.packageName,
                    info.serviceInfo.name,
                ),
                label = info.loadLabel(context.packageManager).toString(),
            )
        }

    /**
     * The Keyguard provider for [action], or null when the component is
     * disabled. Ties are broken by package name so a debug build installed next
     * to a release build resolves deterministically rather than by install order.
     */
    fun resolve(
        context: Context,
        action: String,
    ): Provider? = resolveAll(context, action)
        .filter { it.component.packageName.startsWith(KEYGUARD_PACKAGE_PREFIX) }
        .minByOrNull { it.component.packageName }
}
