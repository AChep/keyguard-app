package com.artemchep.keyguard.common.model

import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.ui.icons.generateAccentColors
import kotlin.time.Instant

data class DGlobalUrlBlock(
    val id: String? = null,
    val name: String,
    val description: String,
    val uri: String,
    val mode: MatchDetection,
    val createdDate: Instant,
    val enabled: Boolean,
    val exposed: Boolean,
    val scope: UrlBlockScope = UrlBlockScope.ALL,
) : Comparable<DGlobalUrlBlock> {
    val accentColor = run {
        val colors = generateAccentColors(uri)
        colors
    }

    override fun compareTo(other: DGlobalUrlBlock): Int {
        return AlphabeticalSort.compareStr(uri, other.uri)
    }
}

/**
 * What a blocked URI blocks.
 *
 * @property key names the scope in the databases and in the records linked apps sync; it
 *   never changes.
 */
enum class UrlBlockScope(
    val key: String,
) {
    /** Autofill and the offer to save a login. */
    ALL(key = "all"),

    /** Only the offer to save a login: autofill still fills. */
    SAVE(key = "save"),
    ;

    companion object {
        /** The scope with the [key]; an unknown one blocks everything, see [ALL]. */
        fun ofKey(key: String): UrlBlockScope = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/** Whether the [uri] is not blank and, in the regex [mode], compiles; literal modes accept any URI. */
internal fun isUrlBlockUriValid(uri: String, mode: MatchDetection): Boolean =
    uri.isNotBlank() && (mode != MatchDetection.RegularExpression || runCatching { uri.toRegex() }.isSuccess)
