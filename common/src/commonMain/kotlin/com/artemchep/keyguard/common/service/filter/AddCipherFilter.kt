package com.artemchep.keyguard.common.service.filter

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.service.filter.model.AddCipherFilterRequest

interface AddCipherFilter : (AddCipherFilterRequest) -> IO<Unit>
