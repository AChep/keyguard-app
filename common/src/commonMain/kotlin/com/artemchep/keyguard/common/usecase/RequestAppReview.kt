package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.platform.LeContext

/**
 * @author Artem Chepurnyi
 */
interface RequestAppReview : (LeContext) -> IO<Unit>
