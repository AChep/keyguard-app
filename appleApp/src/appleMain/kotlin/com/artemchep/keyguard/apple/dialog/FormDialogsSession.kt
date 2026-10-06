package com.artemchep.keyguard.apple.dialog

import com.artemchep.keyguard.apple.core.KeyguardCancellable

/** Dialog channels shared by independent form presentations. Main-confined. */
@Suppress("TooManyFunctions")
class FormDialogsSession internal constructor(internal val controller: DialogController) {
    // The controller ignores every call once closed.
    fun close() = controller.close()

    fun observeConfirmation(
        onChange: (ConfirmationSnapshot?) -> Unit,
    ): KeyguardCancellable = controller.observeConfirmation(onChange)

    fun setConfirmationItemBoolean(key: String, value: Boolean) =
        controller.setConfirmationItemBoolean(key, value)

    fun setConfirmationItemString(key: String, text: String) =
        controller.setConfirmationItemString(key, text)

    fun selectConfirmationItemEnum(key: String, optionKey: String) =
        controller.selectConfirmationItemEnum(key, optionKey)

    fun addConfirmationItem() =
        controller.addConfirmationItem()

    fun removeConfirmationItem(key: String) =
        controller.removeConfirmationItem(key)

    fun selectConfirmationItemFile(key: String) =
        controller.selectConfirmationItemFile(key)

    fun openConfirmationItemDoc(key: String) =
        controller.openConfirmationItemDoc(key)

    fun clearConfirmationItemFile(key: String) =
        controller.clearConfirmationItemFile(key)

    fun confirmConfirmation() =
        controller.confirmConfirmation()

    fun closeConfirmation() =
        controller.closeConfirmation()

    fun observeCipherLinkPicker(
        onChange: (CipherLinkPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = controller.observeCipherLinkPicker(onChange)

    fun setCipherLinkPickerQuery(text: String) =
        controller.setCipherLinkPickerQuery(text)

    fun selectCipherLinkPickerItem(id: String) =
        controller.selectCipherLinkPickerItem(id)

    fun closeCipherLinkPicker() =
        controller.closeCipherLinkPicker()

    fun observeAccountPicker(
        onChange: (AccountPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = controller.observeAccountPicker(onChange)

    fun setAccountPickerNewFolderName(text: String) =
        controller.setAccountPickerNewFolderName(text)

    fun selectAccountPickerItem(key: String) =
        controller.selectAccountPickerItem(key)

    fun confirmAccountPicker() =
        controller.confirmAccountPicker()

    fun closeAccountPicker() =
        controller.closeAccountPicker()
}
