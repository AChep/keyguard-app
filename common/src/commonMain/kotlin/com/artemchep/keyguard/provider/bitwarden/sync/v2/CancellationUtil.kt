package com.artemchep.keyguard.provider.bitwarden.sync.v2

import kotlinx.coroutines.CancellationException

internal fun Throwable.throwIfCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
