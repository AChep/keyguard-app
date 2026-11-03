package com.artemchep.keyguard.common.service.imports.impl

import com.artemchep.keyguard.common.io.throwIfFatalOrCancellation
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.service.imports.ImportFormat
import com.artemchep.keyguard.common.service.imports.ImportPlan
import com.artemchep.keyguard.common.service.imports.ImportSkipReason
import com.artemchep.keyguard.common.service.imports.ImportSkips
import com.artemchep.keyguard.common.service.imports.mapping.IMPORT_DEFAULT_FOLDER_TITLE
import com.artemchep.keyguard.common.util.SkipTally

/**
 * Folder paths deeper than this are cut at this depth; the remaining segments
 * join the last folder's title. Bounds the work a hostile path can cause.
 */
internal const val IMPORT_MAX_FOLDER_DEPTH = 32

/**
 * Collects the folders, items and skips of one import while an importer walks
 * its file.
 */
internal class ImportPlanBuilder(
    private val source: ImportPlan.Source,
) {
    /**
     * A builder for a file of [format], whose source is the format's product.
     */
    constructor(
        format: ImportFormat,
    ) : this(
        source = ImportPlan.Source(
            displayName = format.title,
        ),
    )

    private val folders = mutableListOf<ImportPlan.Folder>()
    private val folderKeyByPath = mutableMapOf<List<String>, String>()
    private val items = mutableListOf<ImportPlan.Item>()
    private var skips = SkipTally.Builder(ImportSkipReason::class)

    /** See [ImportPlan.sourceAccountCount]. */
    var sourceAccountCount: Int = 1

    /**
     * The skips counted so far: within a [record], only that record's.
     */
    val recordSkips: ImportSkips
        get() = skips.build()

    /**
     * Returns the plan key of the folder at [path], planning it and every
     * missing ancestor first. Blank segments are dropped; an empty path is the
     * account root.
     */
    fun folder(
        path: List<String>,
    ): String? {
        val segments = path
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { segments ->
                if (segments.size <= IMPORT_MAX_FOLDER_DEPTH) {
                    segments
                } else {
                    val head = segments.take(IMPORT_MAX_FOLDER_DEPTH - 1)
                    val tail = segments
                        .drop(IMPORT_MAX_FOLDER_DEPTH - 1)
                        .joinToString(separator = "/")
                    head + tail
                }
            }
        var parentKey: String? = null
        for (depth in segments.indices) {
            val prefix = segments.subList(0, depth + 1)
            parentKey = folderKeyByPath.getOrPut(prefix) {
                val key = "folder-${folders.size}"
                folders += ImportPlan.Folder(
                    key = key,
                    parentKey = parentKey,
                    title = prefix.last(),
                )
                key
            }
        }
        return parentKey
    }

    /**
     * Plans a folder called [title] under the folder [parentKey] and returns its
     * key. Unlike the path form, each call plans a folder of its own, so two
     * siblings with the same title stay apart. A blank [title] becomes
     * [IMPORT_DEFAULT_FOLDER_TITLE].
     */
    fun folder(
        parentKey: String?,
        title: String,
    ): String {
        val key = "folder-${folders.size}"
        folders += ImportPlan.Folder(
            key = key,
            parentKey = parentKey,
            title = title.takeIf { it.isNotBlank() }
                ?: IMPORT_DEFAULT_FOLDER_TITLE,
        )
        return key
    }

    fun item(
        request: CreateRequest,
        folderKey: String? = null,
        attachments: List<ImportPlan.Attachment> = emptyList(),
        archived: Boolean = false,
        trashed: Boolean = false,
    ) {
        items += ImportPlan.Item(
            request = request,
            folderKey = folderKey,
            attachments = attachments,
            archived = archived,
            trashed = trashed,
        )
    }

    /**
     * Counts [count] times [reason], attributed to the item called [title] if
     * known.
     */
    fun skip(
        reason: ImportSkipReason,
        title: String? = null,
        count: Int = 1,
    ) {
        skips.add(reason, title, count)
    }

    /**
     * Runs [block] for one record. Whatever the record fails with becomes a
     * counted [ImportSkipReason.Item] skip instead of failing the import.
     * Skips accumulated within a failed record are discarded.
     */
    // Every failure discards local skips; cancellation and fatal errors still propagate.
    @Suppress("TooGenericExceptionCaught")
    inline fun record(
        title: String?,
        block: () -> Unit,
    ) {
        val parentSkips = skips
        val recordSkips = SkipTally.Builder(ImportSkipReason::class)
        skips = recordSkips
        var failed = false
        try {
            block()
        } catch (e: Throwable) {
            failed = true
            e.throwIfFatalOrCancellation()
            parentSkips.add(ImportSkipReason.Item, title)
        } finally {
            skips = parentSkips
            if (!failed) {
                parentSkips.add(recordSkips)
            }
        }
    }

    fun build(): ImportPlan = ImportPlan(
        source = source,
        sourceAccountCount = sourceAccountCount,
        folders = folders.toList(),
        items = items.toList(),
        skips = skips.build(),
    )
}
