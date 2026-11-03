package com.artemchep.keyguard.common.service.session

import com.artemchep.keyguard.common.model.MasterKdfVersion
import com.artemchep.keyguard.common.model.MasterKey
import com.artemchep.keyguard.common.model.MasterSession
import com.artemchep.keyguard.common.service.vault.testVaultSession
import kotlin.time.Clock

/** [MasterSession.Key.Authenticated] means the user just unlocked; persisted means restored. */
internal fun testMasterSessionKey(
    origin: MasterSession.Key.Origin = MasterSession.Key.Authenticated,
) = MasterSession.Key(
    masterKey = MasterKey(MasterKdfVersion.LATEST, byteArrayOf(1)),
    session = testVaultSession {},
    origin = origin,
    createdAt = Clock.System.now(),
)
