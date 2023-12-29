package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.exception.PremiumException
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.io.handleErrorTap
import com.artemchep.keyguard.common.io.io
import com.artemchep.keyguard.common.io.ioEffect
import com.artemchep.keyguard.common.io.map
import com.artemchep.keyguard.common.io.toIO
import com.artemchep.keyguard.platform.recordException
import kotlinx.coroutines.flow.Flow

interface GetPurchased : () -> Flow<Boolean>

fun <T> IO<T>.premium(
    getPurchased: () -> Flow<Boolean>,
    predicate: IO<Boolean> = io(true),
): IO<T> = ioEffect {
    val isPremium = getPurchased()
        .toIO()
        .handleErrorTap { e ->
            val newException = RuntimeException(
                "Failed to obtain the Purchase status.",
                e,
            )
            recordException(newException)
        }
        .bind()
    if (!isPremium) {
        val needsPremium = predicate
            .bind()
        if (needsPremium) {
            throw PremiumException()
        }
    }

    bind()
}

fun <T> IO<T>.unit() = map { Unit }
