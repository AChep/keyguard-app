package com.artemchep.keyguard.crypto

import kotlinx.io.RawSink
import kotlinx.io.RawSource

internal interface PrivateTemporaryStorage : AutoCloseable {
    /** Returns the only writable view of this storage. */
    fun sink(): RawSink

    /**
     * Permanently closes the writable view and freezes the stored bytes for subsequent reads.
     * After this succeeds, the contents must remain unchanged until [close].
     */
    fun sealForReading()

    /** Returns a new source positioned at byte zero. */
    fun source(): RawSource
}

internal expect fun createPrivateTemporaryStorage(): PrivateTemporaryStorage
