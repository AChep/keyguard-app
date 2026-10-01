package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.TextFieldSnapshot

enum class AddItemKind {
    TITLE,
    USERNAME,
    PASSWORD,
    TEXT,
    LINK,
    NOTE,
    TOTP,
    URL,
    FIELD_TEXT,
    FIELD_SWITCH,
    FIELD_LINKED_ID,
    TAG,
    PASSKEY,
    ATTACHMENT,
    SSH_KEY,
    GPG_KEY,
    ENUM_FIELD,
    SWITCH_FIELD,
    SECTION,
    DATE_MONTH_YEAR,
    DATE_TIME,
    ADD,
    SUGGESTION,
}

/** [id] routes back to [KeyguardCore.invokeAddAction]; [selected] marks the chosen entry of a dropdown. */
data class AddActionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/**
 * Arguments for [KeyguardCore.observeAutofillGenerator]; [uris] is the cipher's URI context for the
 * username / email generators.
 */
data class AddAutofillSnapshot(
    val username: Boolean,
    val password: Boolean,
    val uris: List<String>,
)

data class AddTextFieldSnapshot(
    val field: TextFieldSnapshot,
    val label: String?,
    val hidden: Boolean,
    val multiline: Boolean,
    /**
     * The in-form generate button (the shared `AutofillButton`), set only for the username and password fields.
     * Write the generated value back through [KeyguardCore.setAddFieldText], not [KeyguardCore.setAddField].
     */
    val autofill: AddAutofillSnapshot? = null,
) {
    // Flat accessors for the SwiftUI add form over the shared [TextFieldSnapshot].
    val id get() = field.id
    val value get() = field.text
    val textRevision get() = field.textRevision
    val placeholder get() = field.placeholder
    val error get() = field.error
}

/** Size / sync metadata for an [AddItemKind.ATTACHMENT] row (its name is an editable field). */
data class AddAttachmentSnapshot(
    val size: String?,
    val synced: Boolean,
    /**
     * The hint shown while a file is dragged over the row, or null if the row
     * does not accept drops. A drop goes to [KeyguardCore.dropFileOnAddItem].
     */
    val dropText: String? = null,
)

/** The decoded key material of an [AddItemKind.SSH_KEY] row. [hasKey] is false before generate/import. */
data class AddSshKeySnapshot(
    val publicKey: String,
    val privateKey: String,
    val fingerprint: String,
    val hasKey: Boolean,
    val canChange: Boolean,
)

/** The key material of an [AddItemKind.GPG_KEY] row. [hasKey] is false before generate/import. */
data class AddGpgKeySnapshot(
    val publicKey: String,
    val privateKey: String,
    val fingerprint: String,
    val userId: String,
    val hasKey: Boolean,
    val canChange: Boolean,
)

data class AddMergeSourceSnapshot(
    val id: String,
    val title: String,
)

data class AddMergeSnapshot(
    val sources: List<AddMergeSourceSnapshot>,
    val note: String?,
    val actions: List<AddActionSnapshot>,
    val canChange: Boolean,
)

/**
 * The populated fields depend on [kind]:
 *  - editable text lives in [fields] (routed via [KeyguardCore.setAddField]); a custom text field has two
 *    (name, value), a month / year date two (month, year), other items at most one;
 *  - a toggle lives in [switchValue] / [switchEnabled] / [switchId] (routed via
 *    [KeyguardCore.setAddSwitch]);
 *  - a current dropdown value is [enumValue], its choices [options];
 *  - inline / overflow actions live in [actions];
 *  - [options] and [actions] entries route via [KeyguardCore.invokeAddAction].
 */
data class AddItemSnapshot(
    val id: String,
    val kind: AddItemKind,
    val title: String?,
    val text: String?,
    val fields: List<AddTextFieldSnapshot>,
    val switchValue: Boolean,
    val switchEnabled: Boolean,
    val switchId: String?,
    val enumValue: String?,
    val dateText: String?,
    val timeText: String?,
    val attachment: AddAttachmentSnapshot?,
    val sshKey: AddSshKeySnapshot?,
    val gpgKey: AddGpgKeySnapshot?,
    val passkeyName: String?,
    /**
     * TOTP rows only: pass a raw scanned `otpauth://` URI or Base32 secret to [KeyguardCore.scanAddTotp] with
     * this id. Null when the form can't accept a scan.
     */
    val totpScanId: String?,
    val options: List<AddActionSnapshot>,
    val actions: List<AddActionSnapshot>,
)

/**
 * The "Save to" account row: [title] / [text] are the account name and email. [canPick] gates the row tap,
 * which goes to [KeyguardCore.invokeAddOwnership] and opens the account picker.
 */
data class AddOwnershipSnapshot(
    val title: String,
    val text: String?,
    val canPick: Boolean,
)

/** Shared by the cipher and Send forms. Submit via [KeyguardCore.submitAddItem] when [canSave] is true. */
data class AddItemFormSnapshot(
    val loaded: Boolean,
    val title: String,
    val canSave: Boolean,
    /** Null while loading or when the form has no ownership. */
    val ownership: AddOwnershipSnapshot?,
    val merge: AddMergeSnapshot? = null,
    val items: List<AddItemSnapshot>,
    val actions: List<AddActionSnapshot>,
    /**
     * The hint shown while a file is dragged over the form, or null if the form
     * does not accept drops. A drop goes to [KeyguardCore.dropFileOnAddForm].
     */
    val fileDropText: String? = null,
) {
    companion object {
        val empty = AddItemFormSnapshot(
            loaded = false,
            title = "",
            canSave = false,
            ownership = null,
            items = emptyList(),
            actions = emptyList(),
        )
    }
}

/** [requestId] routes the choice back via [KeyguardCore.resolveAddFilePicker] / [KeyguardCore.cancelAddFilePicker]. */
data class AddFilePickerRequest(
    val requestId: String,
    val kind: AddFilePickerKind,
    val mimeTypes: List<String>,
    val suggestedName: String?,
)

enum class AddFilePickerKind {
    OPEN_DOCUMENT,
    OPEN_DIRECTORY,
    NEW_DOCUMENT,
}

/**
 * Swift feeds the choice back through [KeyguardCore.resolveAddDatePicker] / [KeyguardCore.cancelAddDatePicker].
 * Dates use [year] / [month] (1-12) / [day]; times use [hour] (0-23) / [minute]; unused components are
 * placeholders. The inclusive `min*` / `max*` range is valid only when [hasRange]. [presentsInAddForm] is false
 * when the day picker comes from another screen.
 */
data class AddDatePickerRequest(
    val requestId: String,
    val kind: AddDatePickerKind,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val minYear: Int,
    val minMonth: Int,
    val minDay: Int,
    val maxYear: Int,
    val maxMonth: Int,
    val maxDay: Int,
    val hasRange: Boolean,
    val presentsInAddForm: Boolean = true,
)

enum class AddDatePickerKind {
    MONTH_YEAR,
    DATE,
    TIME,
}

/**
 * Swift opens the form keyed by [requestId], runs [KeyguardCore.observeEditCipher] or
 * [KeyguardCore.observeEditSend] per [isSend], and calls [KeyguardCore.clearEditForm] once it is dismissed.
 */
data class AddEditFormRequest(
    val requestId: String,
    val isSend: Boolean,
)
