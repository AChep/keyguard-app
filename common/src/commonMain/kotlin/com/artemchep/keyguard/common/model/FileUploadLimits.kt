package com.artemchep.keyguard.common.model

// KDBX binary field sizes are signed 32-bit values, allowing roughly 2 GiB per attachment.
// We cap attachments at 500 MiB purely to avoid degrading sync performance too much.
internal const val KEEPASS_FILE_UPLOAD_MAX_BYTES: Long = 500L * 1024L * 1024L
