package com.artemchep.keyguard.android.downloader

import arrow.core.left
import arrow.core.right

private const val SEPARATOR = "|"

class AttachmentDownloadTag(
    val localCipherId: String, // to be able to find the right account for the item
    val remoteCipherId: String,
    val attachmentId: String,
) {
    companion object
}

fun AttachmentDownloadTag.Companion.deserialize(tag: String) = kotlin.run {
    val ids = tag.split(SEPARATOR)
    if (ids.size >= 3) {
        val model = AttachmentDownloadTag(
            localCipherId = ids[0],
            remoteCipherId = ids[1],
            attachmentId = ids[2],
        )
        model.right()
    } else {
        IllegalArgumentException("Attachment downloading tag must consist of 3 IDs!").left()
    }
}

fun AttachmentDownloadTag.serialize() =
    listOf(
        localCipherId,
        remoteCipherId,
        attachmentId,
    ).joinToString(separator = SEPARATOR)
