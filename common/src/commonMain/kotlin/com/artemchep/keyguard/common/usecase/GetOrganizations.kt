package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DOrganization
import kotlinx.coroutines.flow.Flow

/**
 * Provides a list of all available on the
 * device organizations.
 */
interface GetOrganizations : () -> Flow<List<DOrganization>>
