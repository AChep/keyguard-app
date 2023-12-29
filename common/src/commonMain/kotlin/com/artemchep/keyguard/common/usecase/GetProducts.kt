package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.Product
import kotlinx.coroutines.flow.Flow

interface GetProducts : () -> Flow<List<Product>?>
