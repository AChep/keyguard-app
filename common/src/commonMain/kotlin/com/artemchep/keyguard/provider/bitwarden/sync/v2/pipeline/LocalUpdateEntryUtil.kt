package com.artemchep.keyguard.provider.bitwarden.sync.v2.pipeline

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService

internal inline fun <
    Server,
    Local : BitwardenService.Has<Local>,
> LocalUpdateEntry<Server, Local>.writeIfCurrent(
    current: Local?,
    write: () -> Unit,
): Boolean {
    if (!shouldUpdate(current)) return false
    write()
    return true
}
