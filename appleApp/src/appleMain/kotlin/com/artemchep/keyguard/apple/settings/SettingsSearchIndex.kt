@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package com.artemchep.keyguard.apple.settings

import platform.Foundation.NSCaseInsensitiveSearch
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.stringByFoldingWithOptions

private val whitespace = Regex("\\s+")

data class SettingsSearchEntrySnapshot(
    val id: String,
    val title: String,
    val categoryId: String,
    val target: SettingsSearchTarget?,
    /** Category and section of a control; `null` for a category itself. */
    val path: String?,
    val description: String,
    val keywords: String,
)

/** Small, pre-normalized local index. Ties retain catalog order. */
class SettingsSearchIndex(
    entries: List<SettingsSearchEntrySnapshot>,
    localeIdentifier: String,
) {
    private val locale = NSLocale(localeIdentifier)
    private val documents = entries.distinctBy { it.id }.map { entry ->
        Document(
            entry = entry,
            title = normalize(entry.title),
            details = normalize("${entry.path.orEmpty()} ${entry.description} ${entry.keywords}"),
        )
    }

    fun search(query: String): List<SettingsSearchEntrySnapshot> {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return emptyList()
        val words = normalized.split(' ')
        return documents.mapNotNull { document ->
            val score = when {
                document.title == normalized -> 4
                document.title.startsWith(normalized) -> 3
                words.all { it in document.title } -> 2
                words.all { it in document.title || it in document.details } -> 1
                else -> return@mapNotNull null
            }
            document.entry to score
        }.sortedByDescending { it.second }.map { it.first }
    }

    private fun normalize(value: String): String = NSString.create(string = value)
        .stringByFoldingWithOptions(NSCaseInsensitiveSearch or NSDiacriticInsensitiveSearch, locale)
        .split(whitespace)
        .filter(String::isNotEmpty)
        .joinToString(" ")

    private data class Document(
        val entry: SettingsSearchEntrySnapshot,
        val title: String,
        val details: String,
    )
}
