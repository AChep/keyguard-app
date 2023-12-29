package com.artemchep.keyguard.common.service.review

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeContext

interface ReviewService {
    fun request(
        context: LeContext,
    ): IO<Unit>
}
