package com.artemchep.keyguard.provider.keyguard.applink

import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.DGlobalUrlBlock
import com.artemchep.keyguard.common.model.MatchDetection
import com.artemchep.keyguard.common.service.urlblock.UrlBlockRepositoryImpl
import com.artemchep.keyguard.feature.p2psync.model.EntryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

// TEST ONLY: the blocked URIs of a test device.

private val AppLinkTestDevice.urlBlocks
    get() = UrlBlockRepositoryImpl(
        vaultDatabaseManager = dbManager,
        exposedDatabaseManager = exposedDbManager,
        dispatcher = Dispatchers.Unconfined,
        recorder = components.recorder,
    )

// Every field of a rule has a default.
@Suppress("LongParameterList")
internal suspend fun AppLinkTestDevice.addUrlBlock(
    name: String,
    description: String = "",
    uri: String = "example.com",
    mode: MatchDetection = MatchDetection.Domain,
    enabled: Boolean = true,
    exposed: Boolean = true,
) = putUrlBlock(
    DGlobalUrlBlock(
        name = name,
        description = description,
        uri = uri,
        mode = mode,
        createdDate = now(),
        enabled = enabled,
        exposed = exposed,
    ),
)

// Every field of a rule has a default.
@Suppress("LongParameterList")
internal suspend fun AppLinkTestDevice.editUrlBlock(
    current: DGlobalUrlBlock,
    name: String = current.name,
    description: String = current.description,
    uri: String = current.uri,
    mode: MatchDetection = current.mode,
    enabled: Boolean = current.enabled,
    exposed: Boolean = current.exposed,
) = putUrlBlock(
    current.copy(
        name = name,
        description = description,
        uri = uri,
        mode = mode,
        enabled = enabled,
        exposed = exposed,
    ),
)

private suspend fun AppLinkTestDevice.putUrlBlock(model: DGlobalUrlBlock) {
    dbManager.mutate("test") { urlBlocks.put(model).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.removeUrlBlock(current: DGlobalUrlBlock) {
    dbManager.mutate("test") { urlBlocks.removeByIds(setOfNotNull(current.id)).bind() }.bind()
}

internal suspend fun AppLinkTestDevice.urlBlockList(): List<DGlobalUrlBlock> = urlBlocks.get().first()

internal suspend fun AppLinkTestDevice.urlBlock(name: String): DGlobalUrlBlock =
    urlBlockList().single { it.name == name }

internal suspend fun AppLinkTestDevice.urlBlockSyncId(name: String): String {
    val id = requireNotNull(urlBlock(name).id).toLong()
    return db.urlBlockQueries.getById(id).executeAsOne().syncId
}

/** The names of the blocked URIs the exposed database holds, by their local ids. */
internal fun AppLinkTestDevice.exposedUrlBlocks(): Map<String, String> = exposedDbManager.database
    .urlBlockQueries
    .get(Long.MAX_VALUE)
    .executeAsList()
    .associate { it.id.toString() to it.name }

/** TEST ONLY: the blocked URIs, for [AppSyncRowContractTest]. */
internal object UrlBlockTestRows : AppSyncTestRows {
    private val uris = listOf("example.com", "^https://bank\\.example/.*", "shop.example")
    private val modes = listOf(MatchDetection.Domain, MatchDetection.RegularExpression, MatchDetection.Host)

    override val kind: EntryKind get() = EntryKind.URL_BLOCK

    override suspend fun add(device: AppLinkTestDevice, name: String, variant: Int) = device.addUrlBlock(
        name = name,
        description = "Rule $variant",
        uri = uris[variant],
        mode = modes[variant],
        enabled = variant % 2 == 0,
        exposed = variant % 2 == 0,
    )

    override suspend fun edit(device: AppLinkTestDevice, name: String, newName: String, variant: Int) =
        device.editUrlBlock(
            current = device.urlBlock(name),
            name = newName,
            description = "Rule $variant",
            uri = uris[variant],
            mode = modes[variant],
            enabled = variant % 2 == 0,
            exposed = variant % 2 == 0,
        )

    override suspend fun remove(device: AppLinkTestDevice, name: String) =
        device.removeUrlBlock(device.urlBlock(name))

    override suspend fun synced(device: AppLinkTestDevice): List<String> = device.urlBlockList()
        .map { it.copy(id = null, createdDate = Instant.DISTANT_PAST).toString() }
        .sorted()

    override suspend fun rowId(device: AppLinkTestDevice, name: String): String =
        requireNotNull(device.urlBlock(name).id)

    override suspend fun syncId(device: AppLinkTestDevice, name: String): String = device.urlBlockSyncId(name)
}
