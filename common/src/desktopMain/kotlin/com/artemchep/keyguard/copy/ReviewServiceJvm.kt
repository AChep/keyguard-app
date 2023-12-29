package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.platform.LeContext
import org.kodein.di.DirectDI

class ReviewServiceJvm() : ReviewService {
    constructor(
        directDI: DirectDI,
    ) : this()

    override fun request(
        context: LeContext,
    ): IO<Unit> = ioUnit()
}
