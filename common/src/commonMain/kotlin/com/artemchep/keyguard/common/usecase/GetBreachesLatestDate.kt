package com.artemchep.keyguard.common.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate

interface GetBreachesLatestDate : () -> Flow<LocalDate?>
