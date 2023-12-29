package com.artemchep.keyguard.android.uploader

import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow

interface UploadAttachmentRepository {
    suspend fun add(request: UploadAttachmentRequest)

    suspend fun remove(requestId: String)

    fun getAll(): Flow<ImmutableList<UploadAttachmentRequest>>
}
