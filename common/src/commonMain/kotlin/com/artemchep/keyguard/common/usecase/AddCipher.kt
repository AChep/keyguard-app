package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.create.CreateRequest

interface AddCipher : (
    Map<String?, CreateRequest>,
) -> IO<List<String>>
