package com.artemchep.keyguard.common.util.flow

import kotlinx.coroutines.flow.Flow

inline fun <reified T> List<Flow<T>>.foldAsList() = combineToList()
