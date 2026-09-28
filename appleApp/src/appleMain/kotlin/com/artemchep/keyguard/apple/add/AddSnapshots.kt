package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.pick
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.home.vault.add.AddState
import com.artemchep.keyguard.feature.send.add.SendAddState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.apple.model.TextFieldSnapshot
import com.artemchep.keyguard.res.*
import platform.Foundation.create

/**
 * Discriminator for the flat [AddItemSnapshot]. One value per
 * [com.artemchep.keyguard.feature.add.AddStateItem] variant; [UNKNOWN] is a
 * defensive fallback for any future variant the bridge does not yet project.
 */
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
    UNKNOWN,
}

/**
 * A selectable / clickable action surfaced by an add-form item — a context-menu
 * entry, an enum / match-type dropdown choice, a quick suggestion, or an entry
 * of an "add more" menu. [id] routes back to [KeyguardCore.invokeAddAction];
 * [selected] marks the currently chosen entry of a dropdown.
 */
data class AddActionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)
/**
 * The in-form Autofill / generate affordance carried by a username / password
 * text field. Mirrors the shared `AutofillButton` (the trailing generate button
 * the Compose add form shows on the username / password fields): [username] /
 * [password] pick which kind of value the in-form generator produces, and [uris]
 * is the cipher's current URI context (used by the username / email generators).
 * Swift presents [AutofillGeneratorSheet] from this, then writes the chosen value
 * back through [KeyguardCore.setAddFieldText] (the revision-bumping onSetText path).
 */
data class AddAutofillSnapshot(
    val username: Boolean,
    val password: Boolean,
    val uris: List<String>,
)

/**
 * One editable text field of an add-form item. Most items expose zero or one;
 * a custom text field exposes two (its name + its value). [id] routes edits
 * back to [KeyguardCore.setAddField].
 */
data class AddTextFieldSnapshot(
    val field: TextFieldSnapshot,
    val label: String?,
    val hidden: Boolean,
    val multiline: Boolean,
    /**
     * The in-form Autofill / generate affordance, set only for the username and
     * password fields; null for every other field. Carries the generator flags
     * (username / password) plus the cipher's current context URIs.
     */
    val autofill: AddAutofillSnapshot? = null,
) {
    // Forwarding accessors so the SwiftUI add form keeps reading the field
    // flatly (`field.value`, `field.id`, …) while the projection itself is
    // the shared [TextFieldSnapshot].
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
 * A flat, Swift-friendly projection of one shared
 * [com.artemchep.keyguard.feature.add.AddStateItem]. The populated fields
 * depend on [kind]:
 *  - editable text lives in [fields] (routed via [KeyguardCore.setAddField]);
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
     * For an [AddItemKind.TOTP] row only: the id of the shared producer's QR-scan
     * sink, or null when the form can't accept a scan. The iOS camera scanner feeds
     * a raw scanned string back through [KeyguardCore.scanAddTotp]; the producer
     * parses the `otpauth://` URI / Base32 secret into the form's fields.
     */
    val totpScanId: String?,
    val options: List<AddActionSnapshot>,
    val actions: List<AddActionSnapshot>,
)

/**
 * A flat, Swift-friendly projection of the shared add form's ownership selector
 * (the "Save to" account row). Mirrors the selected
 * [com.artemchep.keyguard.feature.add.AddStateOwnership] account [Element];
 * [title] / [text] are the account name + email, [canPick] gates the row tap.
 * Tapping fires the producer's ownership `onClick` (an `OrganizationConfirmationRoute`,
 * surfaced as the account-picker dialog) via [KeyguardCore.invokeAddOwnership].
 */
data class AddOwnershipSnapshot(
    val title: String,
    val text: String?,
    val canPick: Boolean,
)

/**
 * A flat, Swift-friendly projection of the shared
 * [com.artemchep.keyguard.feature.home.vault.add.AddState] /
 * [com.artemchep.keyguard.feature.send.add.SendAddState] used by the SwiftUI
 * create-item sheet. Built by [KeyguardCore.buildAddItemSnapshot]; both the
 * cipher and Send forms share this shape (the Send form is a strict subset).
 * Submit via [KeyguardCore.submitAddItem] when [canSave] is true.
 */
data class AddItemFormSnapshot(
    val loaded: Boolean,
    val title: String,
    val canSave: Boolean,
    /**
     * The ownership "Save to" account selector, or null while loading / when the
     * form has no ownership (the cipher edit form with a read-only account still
     * shows it disabled). Tapping opens the account picker dialog.
     */
    val ownership: AddOwnershipSnapshot?,
    val merge: AddMergeSnapshot? = null,
    val items: List<AddItemSnapshot>,
    val actions: List<AddActionSnapshot>,
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

/**
 * A file-selection request bubbled up from a running add form's
 * [com.artemchep.keyguard.feature.add.AddStateItem] (attachment upload,
 * SSH-key import, File Send). [requestId] routes the user's choice back to the
 * producer through [KeyguardCore.resolveAddFilePicker] /
 * [KeyguardCore.cancelAddFilePicker].
 */
data class AddFilePickerRequest(
    val requestId: String,
    val kind: AddFilePickerKind,
    val mimeTypes: List<String>,
    val suggestedName: String?,
)

/** Which native panel an [AddFilePickerRequest] maps to. */
enum class AddFilePickerKind {
    OPEN_DOCUMENT,
    OPEN_DIRECTORY,
    NEW_DOCUMENT,
}

/**
 * A request to present a native date / time picker for a running add form's
 * [com.artemchep.keyguard.feature.add.AddStateItem.DateTime] row (the Send custom
 * deletion / expiration date). The shared producer emits a `DateDayPickerRoute` /
 * `TimePickerRoute` (wrapped in a result receiver) when the user taps the date /
 * time button; the bridge stashes its transmitter and surfaces this request, then
 * Swift presents a SwiftUI `DatePicker` sheet and feeds the choice back through
 * [KeyguardCore.resolveAddDatePicker] / [KeyguardCore.cancelAddDatePicker].
 *
 * Dates use [year] / [month] (1-12) / [day]; times use [hour] (0-23) / [minute].
 * The unused component triple is zero for the inactive [kind].
 */
data class AddDatePickerRequest(
    val requestId: String,
    val kind: AddDatePickerKind,
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    // The inclusive selectable-date range (a DATE picker only), or null for no
    // bound; mirrors the shared `DateDayPickerRoute.Args.selectableDates`.
    val minYear: Int,
    val minMonth: Int,
    val minDay: Int,
    val maxYear: Int,
    val maxMonth: Int,
    val maxDay: Int,
    val hasRange: Boolean,
    val presentsInAddForm: Boolean = true,
)

/** Whether an [AddDatePickerRequest] picks a calendar day or a time of day. */
enum class AddDatePickerKind {
    MONTH_YEAR,
    DATE,
    TIME,
}

/**
 * A request to present the edit form for an existing cipher / Send, bubbled up
 * when a shared producer navigates to its `AddRoute` / `SendAddRoute` with a
 * non-null `initialValue` (the cipher / Send detail "edit" or vault list "clone"
 * action). The full args (carrying the model) are stashed inside
 * [com.artemchep.keyguard.apple.add.AddItemController]; Swift opens the edit
 * sheet keyed by [requestId] and starts the matching observation
 * ([KeyguardCore.observeEditCipher] / [KeyguardCore.observeEditSend]).
 */
data class AddEditFormRequest(
    val requestId: String,
    val isSend: Boolean,
)
