package com.artemchep.keyguard.copy

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.io.ioUnit
import com.artemchep.keyguard.common.service.review.ReviewService
import com.artemchep.keyguard.platform.LeContext

object ReviewServiceIos : ReviewService {
    override fun request(context: LeContext): IO<Unit> = ioUnit()
}
