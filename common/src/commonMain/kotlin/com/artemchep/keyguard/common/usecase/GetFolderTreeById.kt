package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DFolderTree
import kotlinx.coroutines.flow.Flow

interface GetFolderTreeById : (
    String,
) -> Flow<DFolderTree?>
