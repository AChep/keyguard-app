package com.artemchep.keyguard.feature.send.add

import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.fileupload.isBitwardenUploadFileSizeAllowed
import com.artemchep.keyguard.feature.fileupload.toAttachmentFileMetadata

internal fun selectedFileToCreateSendFile(
    selectedFile: FilePickerResult?,
): CreateSendRequest.File {
    if (!isBitwardenUploadFileSizeAllowed(selectedFile?.size)) {
        return CreateSendRequest.File()
    }

    val metadata = selectedFile?.toAttachmentFileMetadata()
    return CreateSendRequest.File(
        uri = metadata?.uriString,
        name = metadata?.name,
        size = metadata?.size,
    )
}
