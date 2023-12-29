package com.artemchep.keyguard.provider.bitwarden.usecase.util

import com.artemchep.keyguard.core.store.bitwarden.BitwardenService

fun BitwardenService.canDelete() = true

fun BitwardenService.canEdit() = when (error?.code) {
    // If the cipher was not properly decoded, then
    // prevent it from being edited.
    BitwardenService.Error.CODE_DECODING_FAILED -> false
    else -> true
}
