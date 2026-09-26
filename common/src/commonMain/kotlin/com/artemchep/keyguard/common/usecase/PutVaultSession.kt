package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterSession

/**
 * Publishing takes ownership of a key session: an unpublished candidate is closed on failure,
 * while a published session is owned by the repository even if the caller is then cancelled.
 */
interface PutVaultSession : (MasterSession) -> IO<Unit>
