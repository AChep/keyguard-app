package com.artemchep.keyguard.common.service.imports

/**
 * Reads the attachments of the source an import plan came from: the file of
 * every ref goes to the sink once per call.
 */
interface ImportAttachmentSource {
    suspend fun <T> extract(
        refs: Set<String>,
        sink: ImportAttachmentSink<T>,
    ): Map<String, T>
}
