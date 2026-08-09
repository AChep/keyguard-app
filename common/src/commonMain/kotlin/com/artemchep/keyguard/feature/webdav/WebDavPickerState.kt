package com.artemchep.keyguard.feature.webdav

import androidx.compose.runtime.MutableState
import arrow.core.Either
import com.artemchep.keyguard.common.model.Loadable

data class WebDavPickerState(
    val path: String,
    val breadcrumbs: List<Breadcrumb>,
    val content: Loadable<Either<Throwable, List<Item>>>,
    val fileName: MutableState<String>?,
    val fileNameError: FileNameError?,
    val onConfirm: (() -> Unit)?,
    val onRefresh: () -> Unit,
) {
    data class Breadcrumb(
        val name: String,
        val onClick: (() -> Unit)?,
    )

    data class Item(
        val key: String,
        val name: String,
        val isCollection: Boolean,
        val size: Long?,
        val onClick: (() -> Unit)?,
    )

    enum class FileNameError {
        Required,
        Invalid,
        ExtensionRequired,
        AlreadyExists,
    }
}
