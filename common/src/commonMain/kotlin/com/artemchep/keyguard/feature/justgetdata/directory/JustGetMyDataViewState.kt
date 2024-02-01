package com.artemchep.keyguard.feature.justgetdata.directory

import androidx.compose.runtime.Immutable
import arrow.core.Either
import com.artemchep.keyguard.common.service.justgetmydata.JustGetMyDataServiceInfo

data class JustGetMyDataViewState(
    val content: Either<Throwable, Content>,
    val onClose: (() -> Unit)? = null,
) {
    @Immutable
    data class Content(
        val model: JustGetMyDataServiceInfo,
    ) {
        companion object
    }
}