package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGlobalUrlOverride
import com.artemchep.keyguard.common.service.urloverride.UrlOverrideRepositoryImpl
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

// TEST ONLY: the URL overrides of a test device.

private val AppLinkTestDevice.urlOverrides
    get() = UrlOverrideRepositoryImpl(
        databaseManager = dbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

internal suspend fun AppLinkTestDevice.addUrlOverride(
    name: String,
    regex: String = "^https://example\\.com/.*",
    command: String = "https://example.org/{url:rmvscm}",
    enabled: Boolean = true,
) = putUrlOverride(
    DGlobalUrlOverride(
        name = name,
        regex = regex.toRegex(),
        command = command,
        createdDate = now(),
        enabled = enabled,
    ),
)

internal suspend fun AppLinkTestDevice.editUrlOverride(
    current: DGlobalUrlOverride,
    name: String = current.name,
    regex: String = current.regex.pattern,
    command: String = current.command,
    enabled: Boolean = current.enabled,
) = putUrlOverride(
    current.copy(
        name = name,
        regex = regex.toRegex(),
        command = command,
        enabled = enabled,
        createdDate = now(),
    ),
)

private suspend fun AppLinkTestDevice.putUrlOverride(model: DGlobalUrlOverride) {
    dbManager.mutate("test") { urlOverrides.put(model).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.removeUrlOverride(current: DGlobalUrlOverride) {
    dbManager.mutate("test") { urlOverrides.removeByIds(setOfNotNull(current.id)).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.urlOverrideList(): List<DGlobalUrlOverride> = urlOverrides.get().first()

internal suspend fun AppLinkTestDevice.urlOverride(name: String): DGlobalUrlOverride =
    urlOverrideList().single { it.name == name }

/** The sync id of the URL override [name]; `null` for the built-in one. */
internal suspend fun AppLinkTestDevice.urlOverrideSyncId(name: String): String? {
    val id = requireNotNull(urlOverride(name).id).toLong()
    return db.urlOverrideQueries.getById(id).executeAsOne().syncId
}

/** TEST ONLY: the URL overrides, for [AppSyncRowContractTest]. */
internal object UrlOverrideTestRows : AppSyncTestRows {
    private val regexes = listOf("^https://example\\.com/.*", "^ftp:.*", "^http:.*")
    private val commands = listOf("https://example.org/{url:rmvscm}", "cmd://open {url}", "https://{url:rmvscm}")

    override val kind: EntryKind get() = EntryKind.URL_OVERRIDE

    override suspend fun add(device: AppLinkTestDevice, name: String, variant: Int) = device.addUrlOverride(
        name = name,
        regex = regexes[variant],
        command = commands[variant],
        enabled = variant % 2 == 0,
    )

    override suspend fun edit(device: AppLinkTestDevice, name: String, newName: String, variant: Int) =
        device.editUrlOverride(
            current = device.urlOverride(name),
            name = newName,
            regex = regexes[variant],
            command = commands[variant],
            enabled = variant % 2 == 0,
        )

    override suspend fun remove(device: AppLinkTestDevice, name: String) =
        device.removeUrlOverride(device.urlOverride(name))

    override suspend fun synced(device: AppLinkTestDevice): List<String> = device.urlOverrideList()
        .map { it.copy(id = null, createdDate = Instant.DISTANT_PAST).toString() }
        .sorted()

    override suspend fun rowId(device: AppLinkTestDevice, name: String): String =
        requireNotNull(device.urlOverride(name).id)

    override suspend fun syncId(device: AppLinkTestDevice, name: String): String? = device.urlOverrideSyncId(name)
}
