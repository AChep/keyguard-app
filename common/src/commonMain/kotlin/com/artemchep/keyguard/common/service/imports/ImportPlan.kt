package com.artemchep.keyguard.common.service.imports

import com.artemchep.keyguard.common.model.create.CreateRequest

/**
 * Everything needed to review and then commit an import: the folders to
 * create, the items to create (with their folder assignment), and the counts
 * of whatever could not be represented.
 *
 * The plan is source-format and target-account agnostic — every
 * [CreateRequest] has a `null` ownership, which the committing flow fills in
 * together with the resolved folder ids.
 */
data class ImportPlan(
    val source: Source,
    /**
     * The number of accounts the source contained. Keyguard merges every
     * source account into the single target account; the review screen
     * mentions the source count when it is greater than one.
     */
    val sourceAccountCount: Int = 1,
    /**
     * The folders to create, in an order that guarantees every
     * [parent][Folder.parentKey] appears before its children.
     */
    val folders: List<Folder>,
    val items: List<Item>,
    val skips: ImportSkips,
) {
    /**
     * What the source says about the application that produced it.
     * Display-only.
     */
    data class Source(
        /**
         * A human-readable name of the exporting application.
         */
        val displayName: String? = null,
        /**
         * A machine identifier of the exporting application, e.g. its package
         * name or relying-party identifier.
         */
        val identifier: String? = null,
    )

    /**
     * A folder to create, keyed by a plan-local identifier — real folder ids
     * exist only after the commit step created them.
     */
    data class Folder(
        val key: String,
        /**
         * The plan-local key of the parent folder, or `null` for a root
         * folder.
         */
        val parentKey: String?,
        val title: String,
    )

    /**
     * A vault item to create. [request] carries no ownership; [folderKey]
     * references the planned folder the item belongs to, if any.
     */
    data class Item(
        val request: CreateRequest,
        val folderKey: String?,
        /**
         * Files that came along in the same archive. They are read only when
         * the import is committed, and only for the selected items.
         */
        val attachments: List<Attachment> = emptyList(),
        /**
         * The item is archived in the source; it is created archived.
         */
        val archived: Boolean = false,
        /**
         * The item is in the trash of the source; it is created in the trash.
         */
        val trashed: Boolean = false,
    )

    /**
     * A file inside the imported archive, addressed by its [ref].
     */
    data class Attachment(
        val ref: String,
        val name: String,
        val size: Long?,
    )
}
