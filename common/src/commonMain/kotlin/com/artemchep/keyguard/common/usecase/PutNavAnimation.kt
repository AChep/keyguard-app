package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.NavAnimation

interface PutNavAnimation : (NavAnimation?) -> IO<Unit>
