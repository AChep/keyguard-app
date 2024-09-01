package com.artemchep.keyguard.feature.logs

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import arrow.optics.optics
import com.artemchep.keyguard.common.service.logging.LogLevel
import kotlin.uuid.Uuid

@Immutable
@optics
sealed interface LogsItem {
    companion object

    val id: String

    @Immutable
    data class Section(
        override val id: String = Uuid.random().toString(),
        val text: String? = null,
        val caps: Boolean = true,
    ) : LogsItem {
        companion object
    }

    @Immutable
    data class Value(
        override val id: String,
        val text: AnnotatedString,
        val level: LogLevel,
        val time: String,
    ) : LogsItem {
        companion object;
    }
}
