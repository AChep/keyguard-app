package com.artemchep.keyguard.common.service.export.model

import com.artemchep.keyguard.common.model.DFilter

data class ExportRequest(
    val filter: DFilter,
    val password: String,
    val attachments: Boolean,
)
