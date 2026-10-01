package com.artemchep.keyguard.apple.directory

import com.artemchep.keyguard.common.service.justdeleteme.JustDeleteMeServiceInfo
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo
import com.artemchep.keyguard.common.service.passkey.PassKeyServiceInfo
import com.artemchep.keyguard.common.service.twofa.TwoFaServiceInfo
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import org.jetbrains.compose.resources.StringResource

/** Stable directory-kind identifiers shared with the SwiftUI layer. */
const val DIRECTORY_KIND_TWO_FA = "two_fa"
const val DIRECTORY_KIND_PASSKEYS = "passkeys"
const val DIRECTORY_KIND_GET_MY_DATA = "get_my_data"
const val DIRECTORY_KIND_DELETE_ACCOUNT = "delete_account"

/** The screen title of a directory kind, as the Compose list screens show it. */
internal fun directoryTitle(kind: String): StringResource? = when (kind) {
    DIRECTORY_KIND_TWO_FA -> Res.string.tfa_directory_title
    DIRECTORY_KIND_PASSKEYS -> Res.string.passkeys_directory_title
    DIRECTORY_KIND_GET_MY_DATA -> Res.string.justgetmydata_title
    DIRECTORY_KIND_DELETE_ACCOUNT -> Res.string.justdeleteme_title
    else -> null
}

enum class ServiceDirectoryLoadStatus { LOADING, READY, FAILED }

/** UTF-16 offsets into the row's name. */
data class DirectoryTextRangeSnapshot(val start: Int, val endExclusive: Int)

enum class ServiceDirectoryItemKind {
    SECTION,
    CONTENT,
}

/**
 * One row of a service directory list. [id] routes detail lookups back through
 * [KeyguardCore.observeServiceDirectoryDetail]; [faviconUrl] is the icon
 * image URL, or null if the row has none.
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

/** Localized link titles, resolved once per catalog load or dialog. */
internal class DirectoryLinkTitles(
    val website: String,
    val documentation: String,
    val setup: String,
    val email: String,
)

internal suspend fun directoryLinkTitles(leContext: LeContext) = DirectoryLinkTitles(
    website = textResource(Res.string.uri_action_launch_website_title, leContext),
    documentation = textResource(Res.string.uri_action_launch_docs_title, leContext),
    setup = textResource(Res.string.passkeys_directory_setup_title, leContext),
    email = textResource(Res.string.justdeleteme_send_email_title, leContext),
)

/** One page gets one row, and the URL stays unique as the SwiftUI row id. */
private inline fun directoryLinks(
    block: MutableList<ServiceDirectoryLinkSnapshot>.() -> Unit,
): List<ServiceDirectoryLinkSnapshot> = buildList(block).distinctBy { it.url }

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
    titles: DirectoryLinkTitles,
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = tfa.toList(),
    notes = notes,
    links = directoryLinks {
        url?.let { add(ServiceDirectoryLinkSnapshot(titles.website, it)) }
        documentation?.let { add(ServiceDirectoryLinkSnapshot(titles.documentation, it)) }
    },
)

/**
 * Projects a passkey service model into a flat [ServiceDirectoryDetailSnapshot]
 * (title, the supported feature chips, markdown notes, documentation / setup
 * links). Shared by the directory detail screen and the inactive-passkey dialog
 * the cipher detail opens.
 */
internal fun PassKeyServiceInfo.toServiceDirectoryDetailSnapshot(
    titles: DirectoryLinkTitles,
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = features.toList(),
    notes = notes,
    links = directoryLinks {
        documentation?.let { add(ServiceDirectoryLinkSnapshot(titles.documentation, it)) }
        setup?.let { add(ServiceDirectoryLinkSnapshot(titles.setup, it)) }
    },
)

/**
 * Projects a "Get my data" service model into a flat [ServiceDirectoryDetailSnapshot]
 * (title, the difficulty chip, markdown notes, website / mailto links). Shared by the
 * directory detail screen and the cipher detail's "Get my data" dialog.
 */
internal fun JustGetMyDataServiceInfo.toServiceDirectoryDetailSnapshot(
    titles: DirectoryLinkTitles,
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = listOfNotNull(difficulty),
    notes = notes,
    links = directoryLinks {
        url?.let { add(ServiceDirectoryLinkSnapshot(titles.website, it)) }
        email?.let { add(ServiceDirectoryLinkSnapshot(titles.email, "mailto:$it")) }
    },
)

/**
 * Projects a "How to delete account" service model into a flat
 * [ServiceDirectoryDetailSnapshot] (title, the difficulty chip, markdown notes,
 * website / mailto links). Shared by the directory detail screen and the cipher
 * detail's "How to delete account" dialog.
 */
internal fun JustDeleteMeServiceInfo.toServiceDirectoryDetailSnapshot(
    titles: DirectoryLinkTitles,
    loaded: Boolean = true,
): ServiceDirectoryDetailSnapshot = ServiceDirectoryDetailSnapshot(
    loaded = loaded,
    title = name,
    chips = listOfNotNull(difficulty),
    notes = notes,
    links = directoryLinks {
        url?.let { add(ServiceDirectoryLinkSnapshot(titles.website, it)) }
        email?.let { add(ServiceDirectoryLinkSnapshot(titles.email, "mailto:$it")) }
    },
)
