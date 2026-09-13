package com.artemchep.keyguard.android.ipc

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.artemchep.keyguard.android.BaseApp
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeyRepository
import com.artemchep.keyguard.common.service.logging.LogLevel
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeyRepository
import com.artemchep.keyguard.common.usecase.GetGpgAgent
import com.artemchep.keyguard.common.usecase.GetSshAgent
import com.artemchep.keyguard.common.usecase.GetVaultSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kodein.di.DIAware
import org.kodein.di.instance

fun <T> T.installAndroidIpcProviders() where T : BaseApp, T : DIAware {
    val getSshAgent by instance<GetSshAgent>()
    val getGpgAgent by instance<GetGpgAgent>()
    val getVaultSession by instance<GetVaultSession>()
    val openPgpPublicKeyRepository by instance<GpgPublicKeyRepository>()
    val sshPublicKeyRepository by instance<SshAgentPublicKeyRepository>()
    val logRepository by instance<LogRepository>()
    val scope = ProcessLifecycleOwner.get().lifecycleScope

    scope.launch {
        combine(
            getSshAgent(),
            getGpgAgent(),
        ) { sshEnabled, openPgpEnabled ->
            sshEnabled to openPgpEnabled
        }
            .distinctUntilChanged()
            // Requests wait for the gate to resolve, so a collector that dies
            // before its first emission would stall every one of them until
            // the timeout. Resolve closed instead, and say why.
            .catch { e ->
                logRepository.post(
                    tag = TAG,
                    message = "Failed to read the IPC provider switches, " +
                            "disabling both providers: ${e.message}",
                    level = LogLevel.ERROR,
                )
                emit(false to false)
            }
            .collect { (sshEnabled, openPgpEnabled) ->
                // Publish before the slower work below so a bind arriving
                // mid-update sees the new state as soon as it is known.
                AndroidIpcProviderGate.update(
                    sshEnabled = sshEnabled,
                    openPgpEnabled = openPgpEnabled,
                )
                applyAndroidIpcProviderState(
                    sshEnabled = sshEnabled,
                    openPgpEnabled = openPgpEnabled,
                    sshPublicKeyRepository = sshPublicKeyRepository,
                    openPgpPublicKeyRepository = openPgpPublicKeyRepository,
                    logRepository = logRepository,
                )
            }
    }
    scope.launch {
        getVaultSession()
            .map(::androidIpcSessionIdentity)
            .distinctUntilChanged()
            .collect {
                AndroidIpcApprovalCoordinator.invalidatePrivateGrants()
            }
    }
}

// These values scope approval grants and registration clearing in
// AndroidIpcApprovalCoordinator; they are identity keys, not display
// text — do not reword them for UI purposes. Each service pairs its
// protocol with a localized label on the approval request.
internal const val PROTOCOL_OPENPGP = "OpenPGP"
internal const val PROTOCOL_SSH = "SSH Authentication"

private const val TAG = "AndroidIpcProviders"

/**
 * Brings the provider components and the pre-unlock catalogs in line with the
 * enable switches.
 *
 * The component toggles are system_server round-trips and the catalog clears
 * touch disk, so none of it belongs on the main thread the switches are
 * collected on. None of it may tear down the collector either: the gate would
 * then freeze at the current emission and stop tracking the preference for the
 * life of the process, leaving a provider the user later disabled both
 * reachable and reported as enabled.
 */
private suspend fun BaseApp.applyAndroidIpcProviderState(
    sshEnabled: Boolean,
    openPgpEnabled: Boolean,
    sshPublicKeyRepository: SshAgentPublicKeyRepository,
    openPgpPublicKeyRepository: GpgPublicKeyRepository,
    logRepository: LogRepository,
) {
    runCatching {
        withContext(Dispatchers.Default) {
            setProviderComponentEnabled(
                componentClass = SshAuthenticationService::class.java,
                enabled = sshEnabled,
            )
            setProviderComponentEnabled(
                componentClass = OpenPgpService::class.java,
                enabled = openPgpEnabled,
            )
            if (!sshEnabled) {
                AndroidIpcApprovalCoordinator.invalidateProtocol(PROTOCOL_SSH)
                sshPublicKeyRepository.clear()
                    .bind()
            }
            if (!openPgpEnabled) {
                AndroidIpcApprovalCoordinator.invalidateProtocol(PROTOCOL_OPENPGP)
                openPgpPublicKeyRepository.clear()
                    .bind()
            }
        }
    }.onFailure { e ->
        e.throwIfFatalOrCancellation()
        logRepository.post(
            tag = TAG,
            message = "Failed to apply the IPC provider state " +
                    "(ssh=$sshEnabled, openpgp=$openPgpEnabled): ${e.message}",
            level = LogLevel.ERROR,
        )
    }
}

private fun BaseApp.setProviderComponentEnabled(
    componentClass: Class<*>,
    enabled: Boolean,
) {
    val componentName = ComponentName(this, componentClass)
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    // setComponentEnabledSetting is a system_server round-trip that may
    // persist settings; skip it when the state already matches.
    if (packageManager.getComponentEnabledSetting(componentName) == state) {
        return
    }
    packageManager.setComponentEnabledSetting(
        componentName,
        state,
        PackageManager.DONT_KILL_APP,
    )
}
