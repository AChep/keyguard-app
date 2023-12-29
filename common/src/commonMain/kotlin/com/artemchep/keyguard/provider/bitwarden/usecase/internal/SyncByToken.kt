package com.artemchep.keyguard.provider.bitwarden.usecase.internal

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.core.store.bitwarden.BitwardenToken

interface SyncByToken : (BitwardenToken) -> IO<Unit>
