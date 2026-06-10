package com.artemchep.keyguard.common.service.execute

import com.artemchep.keyguard.common.io.IO

/**
 * Spawns a platform shell command as a fire-and-forget process. Implementations do 
 * not wait for the command to finish and do not collect command output.
 */
interface ExecuteCommand : (String) -> IO<Unit> {
    val interpreter: String?
}
