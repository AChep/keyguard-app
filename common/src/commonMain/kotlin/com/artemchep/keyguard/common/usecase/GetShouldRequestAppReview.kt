package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow

/**
 * @author Artem Chepurnyi
 */
interface GetShouldRequestAppReview : () -> Flow<Boolean>
