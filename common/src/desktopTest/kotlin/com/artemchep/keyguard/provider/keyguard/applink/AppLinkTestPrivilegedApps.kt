package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DPrivilegedApp
import com.artemchep.keyguard.common.service.gpmprivapps.UserPrivilegedAppRepositoryImpl
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first

// TEST ONLY: the privileged apps of a test device. An app is told by its package.

/** TEST ONLY: a SHA-256 fingerprint of a certificate, in the form the privileged apps keep. */
internal val TEST_CERT_FINGERPRINT = List(32) { "AB" }.joinToString(separator = ":")

private val AppLinkTestDevice.privilegedApps
    get() = UserPrivilegedAppRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

internal suspend fun AppLinkTestDevice.addPrivilegedApp(
    packageName: String,
    certFingerprintSha256: String = TEST_CERT_FINGERPRINT,
) = putPrivilegedApp(
    DPrivilegedApp(
        name = null,
        packageName = packageName,
        certFingerprintSha256 = certFingerprintSha256,
        createdDate = now(),
        source = DPrivilegedApp.Source.USER,
    ),
)

private suspend fun AppLinkTestDevice.putPrivilegedApp(model: DPrivilegedApp) {
    dbManager.mutate("test") { privilegedApps.put(model).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.removePrivilegedApp(current: DPrivilegedApp) {
    dbManager.mutate("test") { privilegedApps.removeByIds(setOfNotNull(current.id)).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.privilegedAppList(): List<DPrivilegedApp> = privilegedApps.get().first()

internal suspend fun AppLinkTestDevice.privilegedApp(packageName: String): DPrivilegedApp =
    privilegedAppList().single { it.packageName == packageName }

internal suspend fun AppLinkTestDevice.privilegedAppSyncId(packageName: String): String {
    val id = requireNotNull(privilegedApp(packageName).id).toLong()
    return db.privilegedAppQueries.getById(id).executeAsOne().syncId
}

/** TEST ONLY: the privileged apps, for [AppSyncRowContractTest]. The name of an app is its package. */
internal object PrivilegedAppTestRows : AppSyncTestRows {
    private val fingerprints = listOf("AB", "0F", "C3").map { byte -> TEST_CERT_FINGERPRINT.replace("AB", byte) }

    override val kind: EntryKind get() = EntryKind.PRIVILEGED_APP

    override suspend fun add(device: AppLinkTestDevice, name: String, variant: Int) = device.addPrivilegedApp(
        packageName = name,
        certFingerprintSha256 = fingerprints[variant],
    )

    // The list has no screen that edits an app, but its repository takes the edit.
    override suspend fun edit(device: AppLinkTestDevice, name: String, newName: String, variant: Int) =
        device.putPrivilegedApp(
            device.privilegedApp(name).copy(
                packageName = newName,
                certFingerprintSha256 = fingerprints[variant],
            ),
        )

    override suspend fun remove(device: AppLinkTestDevice, name: String) =
        device.removePrivilegedApp(device.privilegedApp(name))

    override suspend fun synced(device: AppLinkTestDevice): List<String> = device.privilegedAppList()
        .map { it.copy(id = null, createdDate = null).toString() }
        .sorted()

    override suspend fun rowId(device: AppLinkTestDevice, name: String): String =
        requireNotNull(device.privilegedApp(name).id)

    override suspend fun syncId(device: AppLinkTestDevice, name: String): String = device.privilegedAppSyncId(name)
}
