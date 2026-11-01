package com.artemchep.keyguard.common.service.imports

import kotlinx.io.Source

/**
 * Takes the file of an [attachment ref][ImportPlan.Attachment.ref] while an
 * import reads it out of its source.
 *
 * The [source] is plaintext and valid only during the call — the next file
 * of an archive replaces it — so a sink consumes it right away, and never
 * into a plaintext file: the vault encrypts it into its own storage as it
 * streams by.
 */
fun interface ImportAttachmentSink<T> {
    /**
     * Consumes the file of [ref]. Called once per ref of an extraction,
     * possibly for several refs at once.
     */
    suspend fun write(
        ref: String,
        source: Source,
    ): T
}
