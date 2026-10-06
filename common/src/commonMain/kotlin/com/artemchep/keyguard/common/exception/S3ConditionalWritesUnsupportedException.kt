package com.artemchep.keyguard.common.exception

import com.artemchep.keyguard.common.model.NoAnalytics
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

/** The S3 server accepts conditional writes but does not enforce them. */
class S3ConditionalWritesUnsupportedException :
    IllegalStateException("S3 server ignores conditional writes."),
    Readable,
    NoAnalytics {
    override val title: TextHolder
        get() = TextHolder.Res(Res.string.error_s3_conditional_writes_unsupported)
}
