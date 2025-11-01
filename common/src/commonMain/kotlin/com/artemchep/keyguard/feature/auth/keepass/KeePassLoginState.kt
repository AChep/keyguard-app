package com.artemchep.keyguard.feature.auth.keepass

import androidx.compose.runtime.Immutable
import arrow.optics.optics
import com.artemchep.keyguard.feature.auth.common.TextFieldModel2
import com.artemchep.keyguard.feature.auth.bitwarden.BitwardenLoginEvent
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

@Immutable
@optics
data class KeePassLoginState(
    val dbFileState: StateFlow<FileItem>,
    val keyFileState: StateFlow<FileItem>,
    val password: StateFlow<TextFieldModel2>,
    val actionState: StateFlow<Action?>,
    val tabsState: StateFlow<Tabs>,
    val sideEffects: SideEffect = SideEffect(),
    val isLoading: Boolean = false,
    val onLoginClick: (() -> Unit)? = null,
) {
    companion object;

    @Immutable
    data class Action(
        val onClick: () -> Unit,
    )

    @Immutable
    data class Tabs(
        val items: ImmutableList<KeePassLoginType>,
    )

    @Immutable
    data class FileItem(
        val onClick: () -> Unit,
        val onClear: (() -> Unit)? = null,
        val file: File? = null,
    ) {
        @LeParcelize
        @Serializable
        data class File(
            val uri: String,
            val name: String?,
            val size: Long?,
        ) : LeParcelable
    }

    @Immutable
    data class SideEffect(
        val filePickerIntentFlow: Flow<FilePickerIntent<*>> = emptyFlow(),
        val onSuccessFlow: Flow<Unit> = emptyFlow(),
        val onErrorFlow: Flow<BitwardenLoginEvent.Error> = emptyFlow(),
    ) {
        companion object
    }
}
