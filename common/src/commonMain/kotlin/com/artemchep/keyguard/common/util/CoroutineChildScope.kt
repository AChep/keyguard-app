package com.artemchep.keyguard.common.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.plus

val CoroutineScope.job
    get() = coroutineContext[Job]

inline fun CoroutineScope.newChildScope(
    builder: (Job?) -> Job,
): CoroutineScope = this + builder(job)
