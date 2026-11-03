package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DSecret

/**
 * Merges selected items. 
 *
 * If any input is an environment or carries an environment
 * payload, all inputs must belong to the same account; otherwise throws [IllegalArgumentException].
 */
interface CipherMerge : (List<DSecret>) -> DSecret
