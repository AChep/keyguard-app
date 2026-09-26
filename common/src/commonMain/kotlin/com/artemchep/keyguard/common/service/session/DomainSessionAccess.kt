package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.NotificationsWorker
import com.artemchep.keyguard.common.model.CipherFilterContext
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.backup.BackupConfigRepository
import com.artemchep.keyguard.common.service.backup.BackupRunner
import com.artemchep.keyguard.common.service.crypto.GpgKeyMetadataResolver
import com.artemchep.keyguard.common.service.download.KeePassAttachmentSourceLoader
import com.artemchep.keyguard.common.service.exposedaccount.ExposedAccountSyncer
import com.artemchep.keyguard.common.service.gpgagent.GpgPublicKeySyncer
import com.artemchep.keyguard.common.service.gpgkeyserver.GpgKeyserverRefreshWorker
import com.artemchep.keyguard.common.service.licensekey.impl.LicenseSyncer
import com.artemchep.keyguard.common.service.pendinghistory.PendingUsageHistoryFlushRunner
import com.artemchep.keyguard.common.service.sshagent.SshAgentPublicKeySyncer
import com.artemchep.keyguard.common.service.totp.TotpService
import com.artemchep.keyguard.common.usecase.AddGpgUsageHistory
import com.artemchep.keyguard.common.usecase.AddSshUsageHistory
import com.artemchep.keyguard.common.usecase.CipherUrlCheck
import com.artemchep.keyguard.common.usecase.GetAccounts
import com.artemchep.keyguard.common.usecase.GetCiphers
import com.artemchep.keyguard.common.usecase.GetLicensePremium
import com.artemchep.keyguard.common.usecase.GetProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/** Returns dependencies only while the supplied vault session is active. */
fun interface AppWorkerSessionAccess {
    operator fun invoke(session: MasterSession.Key): AppWorkerSessionDependencies?
}

class AppWorkerSessionDependencies(
    val notificationsWorker: NotificationsWorker,
    val exposedAccountSyncer: ExposedAccountSyncer,
    val sshAgentPublicKeySyncer: SshAgentPublicKeySyncer,
    val gpgPublicKeySyncer: GpgPublicKeySyncer,
    val gpgKeyserverRefreshWorker: GpgKeyserverRefreshWorker,
    val licenseSyncer: LicenseSyncer,
)

fun interface PendingUsageHistorySessionAccess {
    operator fun invoke(session: MasterSession.Key): PendingUsageHistoryFlushRunner?
}

fun interface AccountSessionAccess {
    operator fun invoke(session: MasterSession.Key): GetAccounts?
}

fun interface WatchtowerSessionAccess {
    operator fun invoke(session: MasterSession.Key): WatchtowerSessionWorkers?
}

class WatchtowerSessionWorkers(
    private val launchClient: (CoroutineScope) -> Job,
    private val launchNotifications: (CoroutineScope) -> Job,
) {
    fun launch(scope: CoroutineScope) {
        launchClient(scope)
        launchNotifications(scope)
    }
}

fun interface VaultLicenseSessionAccess {
    operator fun invoke(session: MasterSession.Key): GetLicensePremium?
}

internal fun interface AttachmentSessionAccess {
    operator fun invoke(session: MasterSession.Key): KeePassAttachmentSourceLoader?
}

fun interface BackupConfigSessionAccess {
    operator fun invoke(session: MasterSession.Key): BackupConfigRepository?
}

fun interface BackupRunnerSessionAccess {
    operator fun invoke(session: MasterSession.Key): BackupRunner?
}

fun interface SshAgentSessionAccess {
    operator fun invoke(session: MasterSession.Key): SshAgentSessionDependencies?
}

class SshAgentSessionDependencies(
    val getCiphers: GetCiphers,
    val addSshUsageHistory: AddSshUsageHistory?,
    val filterContext: CipherFilterContext,
)

fun interface GpgAgentSessionAccess {
    operator fun invoke(session: MasterSession.Key): GpgAgentSessionDependencies?
}

class GpgAgentSessionDependencies(
    val getCiphers: GetCiphers,
    val addGpgUsageHistory: AddGpgUsageHistory?,
    val metadataResolver: GpgKeyMetadataResolver?,
    val filterContext: CipherFilterContext,
)

fun interface BrowserAutofillSessionAccess {
    operator fun invoke(session: MasterSession.Key): BrowserAutofillSessionDependencies?
}

class BrowserAutofillSessionDependencies(
    val getCiphers: GetCiphers,
    val getProfiles: GetProfiles,
    val cipherUrlCheck: CipherUrlCheck,
    val totpService: TotpService,
    val filterContext: CipherFilterContext,
)
