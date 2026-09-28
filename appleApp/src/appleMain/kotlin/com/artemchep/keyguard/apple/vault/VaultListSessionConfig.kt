package com.artemchep.keyguard.apple.vault

import com.artemchep.keyguard.feature.home.vault.VaultRoute
import com.artemchep.keyguard.feature.home.vault.screen.VaultListPersistence
import com.artemchep.keyguard.feature.home.vault.search.sort.Sort
import com.artemchep.keyguard.feature.localization.TextHolder

/**
 * The exported, flat configuration of one [VaultListSession]. Mirrors the
 * bridge-relevant subset of the shared `VaultRoute.Args` with the usual
 * ObjC conventions: `""` for absent strings and an `Int` tri-state
 * (`-1` any / `0` off / `1` on) for the nullable booleans.
 *
 * The session is used by the main and stacked vault lists.
 */
data class VaultListSessionConfig(
    /** The per-screen persistence namespace for sort, filter, and query memory. */
    val persistenceScope: String = VaultListPersistence.SCREEN_APPLE,
    /**
     * The stacked-list title (e.g. "Archive"); `""` = the main vault list.
     * Exactly like the canonical `VaultRoute.Args.appBar`, a non-empty
     * title also turns OFF quick filters and the always-show-keyboard
     * memory (`canQuickFilter` / `canAlwaysShowKeyboard` derive from it).
     */
    val appBarTitle: String = "",
    /** Shown under [appBarTitle]; `""` if none. Ignored when [appBarTitle] is `""`. */
    val appBarSubtitle: String = "",
    /** Tri-state: `-1` both / `0` only active / `1` only trashed. */
    val trash: Int = 0,
    /** Tri-state: `-1` both / `0` only active / `1` only archived. */
    val archive: Int = 0,
    /**
     * Forces a fixed sort by its stable `Sort.id` (e.g. the watchtower
     * drill-downs); `""` = user-controlled sorting.
     */
    val sortOverrideId: String = "",
    /** This is the main vault list (deeplinked custom filters target it). */
    val main: Boolean = true,
    /** The search also matches password values (the autofill pick screens). */
    val searchByPassword: Boolean = false,
    val canAddSecrets: Boolean = true,
    /**
     * Non-empty = a custom cipher-filter tab: the session resolves the
     * `DCipherFilter` per-unlock from the session sub-DI and builds the
     * canonical filter-tab args (title, filter, no add), OVERRIDING the
     * other fields of this config.
     */
    val cipherFilterId: String = "",
)

/**
 * Projects the flat config into the canonical `VaultRoute.Args`. An empty
 * [VaultListSessionConfig.appBarTitle] maps to a `null` appBar — NOT an
 * empty one — so `canQuickFilter` / `canAlwaysShowKeyboard` derive exactly
 * like the canonical main list's.
 */
internal fun VaultListSessionConfig.toArgs(): VaultRoute.Args {
    fun triState(value: Int): Boolean? = when {
        value < 0 -> null
        value > 0 -> true
        else -> false
    }

    val sort = sortOverrideId
        .takeIf { it.isNotEmpty() }
        ?.let { id ->
            val resolved = Sort.valueOf(id)
            if (resolved == null) {
                println(
                    "[Keyguard][vaultList] unknown sortOverrideId '$id' — " +
                            "falling back to user-controlled sorting!",
                )
            }
            resolved
        }
    val appBar = appBarTitle
        .takeIf { it.isNotEmpty() }
        ?.let { title ->
            VaultRoute.Args.AppBar(
                title = title,
                subtitle = appBarSubtitle
                    .takeIf { it.isNotEmpty() }
                    ?.let { TextHolder.Value(it) },
            )
        }
    return VaultRoute.Args(
        appBar = appBar,
        sort = sort,
        main = main,
        searchBy = if (searchByPassword) {
            VaultRoute.Args.SearchBy.PASSWORD
        } else {
            VaultRoute.Args.SearchBy.ALL
        },
        trash = triState(trash),
        archive = triState(archive),
        canAddSecrets = canAddSecrets,
    )
}
