package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.SshAgentFilter

interface PutSshAgentFilter : (SshAgentFilter) -> IO<Unit>

