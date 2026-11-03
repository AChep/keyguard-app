package com.artemchep.keyguard.common.service.imports.mapping

import com.artemchep.keyguard.common.model.DSecret
import kotlin.time.Instant

/**
 * What every planned item carries whatever its type: its title and note, its
 * flags and tags, its addresses, and where it goes in the vault.
 */
internal data class ImportedItemMeta(
    val now: Instant,
    val title: String?,
    val note: String? = null,
    val favorite: Boolean = false,
    val reprompt: Boolean = false,
    val uris: List<DSecret.Uri> = emptyList(),
    val tags: List<String> = emptyList(),
    /** The plan key of the item's folder, or `null` for the account root. */
    val folderKey: String? = null,
    val archived: Boolean = false,
    val trashed: Boolean = false,
)
