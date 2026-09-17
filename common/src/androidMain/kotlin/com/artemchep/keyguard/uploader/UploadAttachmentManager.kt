package com.artemchep.keyguard.android.uploader

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

class UploadAttachmentManager(
    private val uploadAttachmentRepository: UploadAttachmentRepository,
) {

    private val scope = GlobalScope + SupervisorJob()

    init {
        scope.launch {
            uploadAttachmentRepository
                .getAll()
                .map { requests ->
                }
        }
    }

    private fun UploadAttachmentRequest.Attachment.handle() {
    }
}
