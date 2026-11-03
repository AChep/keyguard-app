package com.artemchep.keyguard.common.service.agent

import com.artemchep.keyguard.common.model.DSecret

/** Checks item state only; protocol-specific key validation is handled separately. */
fun DSecret.isEligibleForAgent(): Boolean = !deleted && !archived
