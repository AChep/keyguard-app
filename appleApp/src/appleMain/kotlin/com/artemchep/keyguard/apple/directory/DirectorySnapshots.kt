package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.apple.KeyguardCore

/** Stable directory-kind identifiers shared with the SwiftUI layer. */
const val DIRECTORY_KIND_TWO_FA = "two_fa"
const val DIRECTORY_KIND_PASSKEYS = "passkeys"
const val DIRECTORY_KIND_GET_MY_DATA = "get_my_data"
const val DIRECTORY_KIND_DELETE_ACCOUNT = "delete_account"

enum class ServiceDirectoryLoadStatus { LOADING, READY, FAILED }

/** UTF-16 offsets into the row's name. */
data class DirectoryTextRangeSnapshot(val start: Int, val endExclusive: Int)

enum class ServiceDirectoryItemKind {
    SECTION,
    CONTENT,
}

/**
 * One row of a service directory list. [id] routes detail lookups back through
 * [KeyguardCore.observeServiceDirectoryDetail]; [faviconUrl] is the raw source
 * URL whose host the SwiftUI layer resolves into an icon.
 */
data class ServiceDirectoryItemSnapshot(
    val id: String,
    val kind: ServiceDirectoryItemKind,
    val name: String,
    val faviconUrl: String?,
    val highlights: List<DirectoryTextRangeSnapshot> = emptyList(),
)

/**
 * A flat, Swift-friendly projection of one of the four service directories.
 * Produced by an entry-owned [ServiceDirectoryListSession].
 */
data class ServiceDirectorySnapshot(
    val status: ServiceDirectoryLoadStatus,
    val query: String,
    val queryRevision: Int,
    val resultQuery: String,
    val searching: Boolean,
    val items: List<ServiceDirectoryItemSnapshot>,
) {
    companion object {
        val empty = ServiceDirectorySnapshot(
            status = ServiceDirectoryLoadStatus.LOADING,
            query = "",
            queryRevision = 0,
            resultQuery = "",
            searching = false,
            items = emptyList(),
        )
    }
}

/** A titled link (website / documentation / mailto) on a directory detail. */
data class ServiceDirectoryLinkSnapshot(
    val title: String,
    val url: String,
)

/**
 * A flat projection of a directory entry's detail; [notes] is markdown.
 * A failed or missing lookup is terminal, distinct from the initial loading state.
 */
data class ServiceDirectoryDetailSnapshot(
    val loaded: Boolean,
    val title: String,
    val chips: List<String>,
    val notes: String?,
    val links: List<ServiceDirectoryLinkSnapshot>,
    val failed: Boolean = false,
    val notFound: Boolean = false,
) {
    companion object {
        val empty = ServiceDirectoryDetailSnapshot(
            loaded = false,
            title = "",
            chips = emptyList(),
            notes = null,
            links = emptyList(),
        )
    }
}

/**
 * Projects a 2FA service model into a flat [ServiceDirectoryDetailSnapshot]
 * (title, the supported 2FA-type chips, markdown notes, website / documentation
 * links). Shared by the directory detail screen and the inactive-TOTP dialog the
 * cipher detail opens.
 */
internal fun TwoFaServiceInfo.toServiceDirectoryDetailSnapshot(
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = tfa.toList(),
    notes = notes,
    links = buildList {
        url?.let { add(ServiceDirectoryLinkSnapshot("Website", it)) }
        documentation?.let { add(ServiceDirectoryLinkSnapshot("Documentation", it)) }
    },
)

/**
 * Projects a passkey service model into a flat [ServiceDirectoryDetailSnapshot]
 * (title, the supported feature chips, markdown notes, documentation / setup
 * links). Shared by the directory detail screen and the inactive-passkey dialog
 * the cipher detail opens.
 */
internal fun PassKeyServiceInfo.toServiceDirectoryDetailSnapshot(
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = features.toList(),
    notes = notes,
    links = buildList {
        documentation?.let { add(ServiceDirectoryLinkSnapshot("Documentation", it)) }
        setup?.let { add(ServiceDirectoryLinkSnapshot("Setup", it)) }
    },
)

/**
 * Projects a "Get my data" service model into a flat [ServiceDirectoryDetailSnapshot]
 * (title, the difficulty chip, markdown notes, website / mailto links). Shared by the
 * directory detail screen and the cipher detail's "Get my data" dialog.
 */
internal fun JustGetMyDataServiceInfo.toServiceDirectoryDetailSnapshot(
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = listOfNotNull(difficulty),
    notes = notes,
    links = buildList {
        url?.let { add(ServiceDirectoryLinkSnapshot("Website", it)) }
        email?.let { add(ServiceDirectoryLinkSnapshot("Email", "mailto:$it")) }
    },
)

/**
 * Projects a "How to delete account" service model into a flat
 * [ServiceDirectoryDetailSnapshot] (title, the difficulty chip, markdown notes,
 * website / mailto links). Shared by the directory detail screen and the cipher
 * detail's "How to delete account" dialog.
 */
internal fun JustDeleteMeServiceInfo.toServiceDirectoryDetailSnapshot(
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = listOfNotNull(difficulty),
    notes = notes,
    links = buildList {
        url?.let { add(ServiceDirectoryLinkSnapshot("Website", it)) }
        email?.let { add(ServiceDirectoryLinkSnapshot("Email", "mailto:$it")) }
    },
)
