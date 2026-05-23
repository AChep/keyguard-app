package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DBarcodeUsageHistory
import kotlinx.coroutines.flow.Flow

interface GetBarcodeUsageHistory : (String) -> Flow<DBarcodeUsageHistory?>
