package com.artemchep.keyguard.feature.gpgagent.tools

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.decrypt
import com.artemchep.keyguard.res.encrypt
import com.artemchep.keyguard.res.gpg_tools_mode_cleartext
import com.artemchep.keyguard.res.gpg_tools_mode_detached
import com.artemchep.keyguard.res.gpg_tools_mode_inline
import com.artemchep.keyguard.res.gpg_tools_scope_file
import com.artemchep.keyguard.res.gpg_tools_scope_text
import com.artemchep.keyguard.res.sign
import com.artemchep.keyguard.res.verify
import com.artemchep.keyguard.ui.icons.KeyguardFileSign
import com.artemchep.keyguard.ui.tabs.TabItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class GpgToolsState(
    val sideEffects: SideEffects = SideEffects(),
    val operation: GpgToolsOperation,
    val scope: GpgToolsScope,
    val scopes: ImmutableList<GpgToolsScope>,
    val signMode: GpgToolsSignMode,
    val signModes: ImmutableList<GpgToolsSignMode>,
    val verifyMode: GpgToolsVerifyMode,
    val verifyModes: ImmutableList<GpgToolsVerifyMode>,
    val armor: Boolean,
    val showArmor: Boolean,
    val inputText: TextFieldModel,
    val detachedSignatureText: TextFieldModel,
    val customPublicKeys: ImmutableList<CustomPublicKeyItem>,
    val inputFile: FileRef?,
    val signatureFile: FileRef?,
    val storedKeys: ImmutableList<KeyItem>,
    val selectedPrivateKeyId: String?,
    val selectedEncryptSigningKeyId: String?,
    val selectedRecipientIds: Set<String>,
    val busy: Boolean,
    val onScopeChange: (GpgToolsScope) -> Unit,
    val onSignModeChange: (GpgToolsSignMode) -> Unit,
    val onVerifyModeChange: (GpgToolsVerifyMode) -> Unit,
    val onArmorChange: (Boolean) -> Unit,
    val onSelectPrivateKey: (String) -> Unit,
    val onSelectEncryptSigningKey: (String?) -> Unit,
    val onToggleRecipient: (String) -> Unit,
    val onSelectInputFile: () -> Unit,
    val onClearInputFile: () -> Unit,
    val onSelectSignatureFile: () -> Unit,
    val onClearSignatureFile: () -> Unit,
    val onAddPublicKey: () -> Unit,
    val onRemovePublicKey: (String) -> Unit,
    val onRun: (() -> Unit)?,
) {
    data class SideEffects(
        val filePickerIntentFlow: Flow<FilePickerIntent<*>> = emptyFlow(),
    )

    @Immutable
    data class FileRef(
        val uri: String,
        val name: String?,
        val size: Long?,
    )

    @Immutable
    data class CustomPublicKeyItem(
        val id: String,
        val publicKey: String,
    )

    @Immutable
    data class KeyItem(
        val id: String,
        val title: String,
        val description: String?,
        val canSign: Boolean,
        val canDecrypt: Boolean,
        val publicKeyAvailable: Boolean,
    )
}

enum class GpgToolsScope(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    TEXT("text", TextHolder.Res(Res.string.gpg_tools_scope_text)),
    FILE("file", TextHolder.Res(Res.string.gpg_tools_scope_file)),
}

enum class GpgToolsOperation(
    override val key: String,
    override val title: TextHolder,
    val icon: ImageVector,
) : TabItem {
    ENCRYPT(
        key = "encrypt",
        title = TextHolder.Res(Res.string.encrypt),
        icon = Icons.Outlined.Lock,
    ),
    DECRYPT(
        key = "decrypt",
        title = TextHolder.Res(Res.string.decrypt),
        icon = Icons.Outlined.LockOpen,
    ),
    SIGN(
        key = "sign",
        title = TextHolder.Res(Res.string.sign),
        icon = Icons.Outlined.KeyguardFileSign,
    ),
    VERIFY(
        key = "verify",
        title = TextHolder.Res(Res.string.verify),
        icon = Icons.Outlined.CheckCircle,
    ),
}

enum class GpgToolsSignMode(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    CLEAR_TEXT("cleartext", TextHolder.Res(Res.string.gpg_tools_mode_cleartext)),
    DETACHED("detached", TextHolder.Res(Res.string.gpg_tools_mode_detached)),
}

enum class GpgToolsVerifyMode(
    override val key: String,
    override val title: TextHolder,
) : TabItem {
    INLINE("inline", TextHolder.Res(Res.string.gpg_tools_mode_inline)),
    DETACHED("detached", TextHolder.Res(Res.string.gpg_tools_mode_detached)),
}
