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

    fun source(): RawSource

    fun rewind()
}

internal expect fun createPrivateTemporaryStorage(): PrivateTemporaryStorage
