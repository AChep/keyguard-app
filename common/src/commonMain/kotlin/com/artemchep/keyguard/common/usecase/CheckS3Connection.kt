package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.S3Location

interface CheckS3Connection : (S3Location) -> IO<Unit>
