package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.CreateSendRequest

interface AddSend : (
    Map<String?, CreateSendRequest>,
) -> IO<List<String>>
