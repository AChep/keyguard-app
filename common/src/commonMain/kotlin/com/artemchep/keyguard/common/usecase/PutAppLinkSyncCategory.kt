package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DAppLinkSyncCategory

/**
 * Turns the sync of a category of records with the linked apps on or off, on this device
 * only. Records that do not sync stay on this device; once the sync is on again, the
 * newest edit of each record wins.
 */
interface PutAppLinkSyncCategory : (DAppLinkSyncCategory, Boolean) -> IO<Unit>
