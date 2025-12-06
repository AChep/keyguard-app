package com.artemchep.keyguard.common.service.database

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKey

interface DatabaseChangePassword {
    /**
     * Changes the password of the database. After executing the function, you should create a
     * new database manager with a new master key.
     */
    fun changePassword(newMasterKey: MasterKey): IO<Unit>
}
