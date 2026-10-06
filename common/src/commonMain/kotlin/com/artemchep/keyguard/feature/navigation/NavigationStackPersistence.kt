package com.artemchep.keyguard.feature.navigation

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.service.keyvalue.KeyValueStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Owns both navigation formats so separate whole-file caches cannot restore stale secrets. */
class NavigationStackPersistence(
    private val store: KeyValueStore,
    private val json: Json,
) {
    enum class Format(val key: String) {
        Compose("router_stacks"),
        Apple("stacks"),
    }

    private val mutex = Mutex()
    private var initialized = false

    suspend fun load(format: Format): Map<String, List<RouteDescriptor>> = mutex.withLock {
        initialize()
        decode(store.getString(format.key, "").first())
    }

    fun save(format: Format, stacks: Map<String, List<RouteDescriptor>>): IO<Unit> = ioEffect {
        mutex.withLock {
            initialize()
            // Filter immediately before encoding; never change the live routes or
            // replace a secret-bearing filter with a broader query.
            val encoded = json.encodeToString(stacks.forPersistence())
            store.getString(format.key, "").setAndCommit(encoded).bind()
        }
    }

    private suspend fun initialize() {
        if (initialized) return
        for (format in Format.entries) {
            val pref = store.getString(format.key, "")
            val safe = json.encodeToString(decode(pref.first()))
            // Also erase malformed/unknown legacy data: a failed decode must not
            // leave its original bytes (possibly containing a password) on disk.
            // Always commit until initialization succeeds: the store may update
            // its cache before a failed disk write, so cached equality is not
            // evidence that the old bytes were removed.
            pref.setAndCommit(safe).bind()
        }
        initialized = true
    }

    private fun decode(raw: String): Map<String, List<RouteDescriptor>> = runCatching {
        json.decodeFromString<Map<String, List<RouteDescriptor>>>(raw)
    }.getOrDefault(emptyMap()).forPersistence()
}

private fun Map<String, List<RouteDescriptor>>.forPersistence() =
    mapValues { (_, routes) -> routes.filter { it.canPersist() } }
        .filterValues { it.isNotEmpty() }

private fun RouteDescriptor.canPersist(): Boolean = when (this) {
    is RouteDescriptor.VaultList -> filter.canPersist()
    is RouteDescriptor.Folders -> filter.canPersist()
    is RouteDescriptor.Duplicates -> filter.canPersist()
    is RouteDescriptor.Export -> filter.canPersist()
    is RouteDescriptor.Watchtower -> filter.canPersist()
    is RouteDescriptor.WatchtowerAlerts -> filter.canPersist()
    is RouteDescriptor.VaultCipherView,
    is RouteDescriptor.SendList,
    is RouteDescriptor.SendView,
    is RouteDescriptor.PasswordHistory,
    is RouteDescriptor.SshAgentHistory,
    RouteDescriptor.PasswordMemory,
    is RouteDescriptor.Generator,
    is RouteDescriptor.WordlistView,
    is RouteDescriptor.Organizations,
    is RouteDescriptor.Collections,
    is RouteDescriptor.EquivalentDomains,
    is RouteDescriptor.CipherFilterView,
    RouteDescriptor.Downloads,
    RouteDescriptor.CipherFilters,
    RouteDescriptor.GeneratorHistory,
    RouteDescriptor.EmailRelayList,
    RouteDescriptor.WordlistList,
    RouteDescriptor.GpgTools,
    RouteDescriptor.Settings,
    RouteDescriptor.Feedback,
    RouteDescriptor.Subscriptions,
    RouteDescriptor.TwoFaServices,
    RouteDescriptor.PasskeysServices,
    RouteDescriptor.JustGetMyDataServices,
    RouteDescriptor.JustDeleteMeServices,
    is RouteDescriptor.Unmapped,
    -> true
}

private fun DFilter?.canPersist(): Boolean = when (this) {
    is DFilter.ByPasswordValue -> false
    is DFilter.And -> filters.all { it.canPersist() }
    is DFilter.Or -> filters.all { it.canPersist() }
    is DFilter.Not -> filter.canPersist()
    null,
    DFilter.All,
    is DFilter.ById,
    DFilter.ByFavorite,
    is DFilter.ByType,
    DFilter.ByOtp,
    DFilter.ByAttachments,
    DFilter.ByPasskeys,
    is DFilter.ByPasswordStrength,
    DFilter.ByWeakSshKeys,
    DFilter.ByUnusableGpgKeys,
    DFilter.ByWeakGpgKeys,
    DFilter.ByGpgKeyPublishing,
    DFilter.ByPasswordDuplicates,
    DFilter.ByPasswordPwned,
    DFilter.ByWebsitePwned,
    DFilter.ByIncomplete,
    DFilter.ByExpiring,
    DFilter.ByUnsecureWebsites,
    DFilter.ByTfaWebsites,
    DFilter.ByPasskeyWebsites,
    DFilter.ByDuplicateWebsites,
    DFilter.ByBroadWebsites,
    is DFilter.BySync,
    is DFilter.ByReprompt,
    is DFilter.ByError,
    DFilter.ByIgnoredAlerts,
    -> true
}
