package com.artemchep.keyguard.apple.dialog

import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewContent
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewJsonError
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewJsonPreview
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewJsonTree
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewJsonType
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.attachment_preview_json_error_duplicate_keys
import com.artemchep.keyguard.res.attachment_preview_json_error_invalid
import com.artemchep.keyguard.res.attachment_preview_json_error_too_deep
import com.artemchep.keyguard.res.attachment_preview_json_error_too_large
import com.artemchep.keyguard.res.attachment_preview_json_error_too_many_nodes
import platform.Foundation.NSUUID

internal fun AttachmentPreviewSnapshot.withJsonDocumentIdentity(
    content: AttachmentPreviewContent,
    previousContent: AttachmentPreviewContent?,
    previousSnapshot: AttachmentPreviewSnapshot?,
): AttachmentPreviewSnapshot = if (content is AttachmentPreviewContent.Json) {
    copy(
        jsonDocumentId = content.preview.documentId(
            previousPreview = (previousContent as? AttachmentPreviewContent.Json)?.preview,
            previousId = previousSnapshot?.jsonDocumentId,
        ),
    )
} else {
    this
}

/** Includes failed parses, which have no tree identity but still own source scroll state. */
internal fun AttachmentPreviewJsonPreview.documentId(
    previousPreview: AttachmentPreviewJsonPreview?,
    previousId: String?,
): String = previousId?.takeIf { this === previousPreview } ?: NSUUID().UUIDString

internal suspend fun AttachmentPreviewContent.Json.toAttachmentPreviewJsonSnapshot(
    fileName: String,
    leContext: LeContext,
    previousJsonTree: AttachmentPreviewJsonTreeSnapshot?,
): AttachmentPreviewSnapshot = toAttachmentPreviewTextSnapshot(fileName, AttachmentPreviewKindSnapshot.JSON).copy(
    jsonTree = preview.tree?.toSnapshot(previousJsonTree),
    jsonErrorMessage = preview.error?.let { error ->
        val resource = when (error) {
            AttachmentPreviewJsonError.Invalid -> Res.string.attachment_preview_json_error_invalid
            AttachmentPreviewJsonError.TooLarge -> Res.string.attachment_preview_json_error_too_large
            AttachmentPreviewJsonError.TooDeep -> Res.string.attachment_preview_json_error_too_deep
            AttachmentPreviewJsonError.TooManyNodes -> Res.string.attachment_preview_json_error_too_many_nodes
            AttachmentPreviewJsonError.DuplicateKeys -> Res.string.attachment_preview_json_error_duplicate_keys
        }
        textResource(resource, leContext)
    },
)

/** Keeps the shared parsed tree alive without reparsing JSON in the native UI. */
class AttachmentPreviewJsonTreeSnapshot internal constructor(
    private val tree: AttachmentPreviewJsonTree,
) {
    val rootId: String get() = tree.rootId

    fun visibleRows(expandedIds: Set<String>): List<AttachmentPreviewJsonNodeSnapshot> =
        tree.visibleRows(expandedIds).map { node ->
            AttachmentPreviewJsonNodeSnapshot(
                id = node.id,
                depth = node.depth,
                key = node.key,
                parentPath = node.parentPath,
                type = when (node.type) {
                    AttachmentPreviewJsonType.Object -> AttachmentPreviewJsonTypeSnapshot.OBJECT
                    AttachmentPreviewJsonType.Array -> AttachmentPreviewJsonTypeSnapshot.ARRAY
                    AttachmentPreviewJsonType.String -> AttachmentPreviewJsonTypeSnapshot.STRING
                    AttachmentPreviewJsonType.Number -> AttachmentPreviewJsonTypeSnapshot.NUMBER
                    AttachmentPreviewJsonType.Boolean -> AttachmentPreviewJsonTypeSnapshot.BOOLEAN
                    AttachmentPreviewJsonType.Null -> AttachmentPreviewJsonTypeSnapshot.NULL
                },
                value = node.value,
                childCount = node.childCount,
                expandable = node.expandable,
            )
        }

    internal fun wraps(other: AttachmentPreviewJsonTree): Boolean = tree === other
}

internal fun AttachmentPreviewJsonTree.toSnapshot(
    previous: AttachmentPreviewJsonTreeSnapshot?,
): AttachmentPreviewJsonTreeSnapshot = previous?.takeIf { it.wraps(this) }
    ?: AttachmentPreviewJsonTreeSnapshot(this)

enum class AttachmentPreviewJsonTypeSnapshot {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL,
}

data class AttachmentPreviewJsonNodeSnapshot(
    val id: String,
    val depth: Int,
    val key: String,
    val parentPath: String,
    val type: AttachmentPreviewJsonTypeSnapshot,
    val value: String?,
    val childCount: Int,
    val expandable: Boolean,
)
