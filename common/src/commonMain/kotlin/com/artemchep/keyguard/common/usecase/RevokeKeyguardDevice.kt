package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.P2pGroupId

/**
 * Removes other devices, by their device ids, from a sync group, such as the one of linked
 * apps. The removals are signed with the authority key of this device, which only the
 * master password opens. The next sync tells the other devices; the removed ones stop
 * syncing.
 */
interface RevokeKeyguardDevice {
    operator fun invoke(
        groupId: P2pGroupId,
        deviceIds: Set<String>,
        password: String,
    ): IO<Unit>
}
