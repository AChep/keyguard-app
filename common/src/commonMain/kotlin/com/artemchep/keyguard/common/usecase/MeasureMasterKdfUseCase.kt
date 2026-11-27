package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.MasterKdf
import kotlin.time.Duration

/**
 * Derives a master key once with the given key derivation, as
 * an unlock does, and returns how long it took.
 */
interface MeasureMasterKdfUseCase : (MasterKdf.Argon2id) -> IO<Duration>
