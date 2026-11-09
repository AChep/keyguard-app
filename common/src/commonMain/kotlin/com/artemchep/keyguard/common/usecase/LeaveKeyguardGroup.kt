package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.P2pGroupId

/**
 * Takes this device out of the sync group. The items
 * stay on this device, in a group of its own.
 *
 * The second argument is the master password: the authority key of this device signs its
 * leaving. A device the group already removed leaves without it.
 */
interface LeaveKeyguardGroup : (P2pGroupId, String?) -> IO<Unit>
