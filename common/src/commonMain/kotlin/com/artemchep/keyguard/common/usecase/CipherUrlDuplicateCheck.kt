package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.DSecret

interface CipherUrlDuplicateCheck : (DSecret.Uri, DSecret.Uri) -> IO<DSecret.Uri?>
