package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DAppLinkSyncCategory
import kotlinx.coroutines.flow.Flow

/** Returns whether this device syncs each category of records with its linked apps. */
interface GetAppLinkSyncCategories : () -> Flow<Map<DAppLinkSyncCategory, Boolean>>
