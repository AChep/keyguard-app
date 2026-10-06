package com.artemchep.keyguard.apple.dialog

import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.apple.core.toArgbLong
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.ui.format
import platform.Foundation.NSData

/**
 * The "Show in Large Type" dialog. [groups] are the phrase groups, each a list of code-point tiles; a tile is
 * highlighted while its [LargeTypeSymbolSnapshot.index] is `<=` [selectedIndex]. [note] is the optional
 * composite-Unicode warning. `null` from [KeyguardCore.observeLargeType] means the dialog is hidden.
 */
data class LargeTypeSnapshot(
    val title: String,
    val note: String?,
    val selectedIndex: Int,
    val groups: List<List<LargeTypeSymbolSnapshot>>,
)

/** One large code-point tile; [color] mirrors the Compose colorize categories. */
data class LargeTypeSymbolSnapshot(
    val text: String,
    val index: Int,
    val color: LargeTypeSymbolColor,
)

enum class LargeTypeSymbolColor {
    PLAIN,
    DIGIT,
    SYMBOL,
}

/**
 * The "Show as Barcode" dialog. SwiftUI renders the bitmap from [data] and [format], a `BarcodeImageFormat` name
 * (e.g. `QR_CODE`). Each of [options] carries an opaque id passed back via [KeyguardCore.selectBarcodeFormat].
 * [note] is the optional caption below the barcode. [formatSelectable] is false when the entry point pins the
 * format (e.g. a TOTP QR code). `null` from [KeyguardCore.observeBarcode] means the dialog is hidden.
 */
data class BarcodeSnapshot(
    val title: String,
    val data: String,
    val format: String,
    val formatTitle: String,
    val note: String?,
    val options: List<BarcodeFormatOptionSnapshot>,
    val formatSelectable: Boolean,
)

data class BarcodeFormatOptionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/**
 * The passkey credential detail dialog. When [error] is set the other fields are absent. [canUse] is true only in
 * the pick-passkey app mode and enables [KeyguardCore.usePasskeyCredential]. `null` from
 * [KeyguardCore.observePasskeyCredential] means the dialog is hidden.
 */
data class PasskeyCredentialSnapshot(
    val title: String,
    val error: String? = null,
    val userDisplayName: String? = null,
    val userName: String? = null,
    val rpId: String? = null,
    val rpName: String? = null,
    val signatureCounter: String? = null,
    val discoverable: Boolean = false,
    val createdAt: String? = null,
    val credentialId: String? = null,
    val canUse: Boolean = false,
)

enum class AttachmentPreviewKindSnapshot {
    LOADING,
    IMAGE,
    TEXT,
    MARKDOWN,
    ERROR,
}

/**
 * One syntax-highlight run over [AttachmentPreviewSnapshot.text]. [colorArgb] is `0` when the run only changes
 * the weight (the highlighter always emits opaque colors, so `0` is never a real value).
 */
data class AttachmentPreviewSpanSnapshot(
    val start: Int,
    val end: Int,
    val colorArgb: Long,
    val bold: Boolean,
)

/**
 * The attachment preview sheet. Which content field is set depends on [kind]: [imageData] for IMAGE (raw
 * decrypted bytes, as NSData so Swift can decode them without per-byte ObjC calls), [text] + [spans] for
 * TEXT / MARKDOWN, [errorMessage] for ERROR. [canCopy] enables [KeyguardCore.invokeAttachmentPreviewCopy].
 * `null` from [KeyguardCore.observeAttachmentPreview] means the dialog is hidden.
 */
data class AttachmentPreviewSnapshot(
    val fileName: String,
    val kind: AttachmentPreviewKindSnapshot,
    val imageData: NSData? = null,
    val text: String? = null,
    val spans: List<AttachmentPreviewSpanSnapshot> = emptyList(),
    val errorMessage: String? = null,
    val imageDecodeErrorMessage: String? = null,
    val canCopy: Boolean = false,
)

/**
 * The generic confirmation dialog. [confirmEnabled] is false while the shared producer's validation fails.
 * `null` from [KeyguardCore.observeConfirmation] means the dialog is hidden.
 */
data class ConfirmationSnapshot(
    val canAddItem: Boolean = false,
    val title: String?,
    val subtitle: String?,
    val message: String?,
    val items: List<ConfirmationItemSnapshot>,
    val confirmEnabled: Boolean,
    val docUrl: String? = null,
)

/**
 * [CHOICE] is the single-select dropdown. It is not named ENUM because that lowercases to the Swift keyword
 * `enum` when exported.
 */
enum class ConfirmationItemKind {
    BOOLEAN,
    STRING,
    CHOICE,
    FILE,
}

/**
 * One row of a [ConfirmationSnapshot]. Only the fields of its [kind] are set; mutations go back through
 * `KeyguardCore` keyed by [key].
 */
data class ConfirmationItemSnapshot(
    val removable: Boolean = false,
    val key: String,
    val kind: ConfirmationItemKind,
    val enabled: Boolean,
    val title: String,
    // BOOLEAN
    val text: String? = null,
    val booleanValue: Boolean = false,
    // STRING
    val stringValue: String = "",
    val stringRevision: Int = 0,
    // Named descriptionText, not description: a Kotlin property exported as
    // `description` collides with NSObject.description (non-optional) on Swift.
    val descriptionText: String? = null,
    val hint: String? = null,
    val error: String? = null,
    val sensitive: Boolean = false,
    val monospace: Boolean = false,
    val password: Boolean = false,
    // CHOICE
    val enumValue: String = "",
    val options: List<ConfirmationEnumOptionSnapshot> = emptyList(),
    val docText: String? = null,
    /** The selected option's doc has a "learn more" link; open it with its [key]. */
    val docHasLink: Boolean = false,
    // FILE
    val fileName: String? = null,
    val hasFile: Boolean = false,
)

/** One option of a [ConfirmationItemKind.CHOICE] row's dropdown. */
data class ConfirmationEnumOptionSnapshot(
    val key: String,
    val title: String,
    val text: String?,
    val selected: Boolean,
)

/**
 * The master-password re-prompt dialog. [confirmEnabled] is false while the producer's validation fails; a wrong
 * password surfaces as a toast, not an inline error. `null` from [KeyguardCore.observeElevatedAccess] means the
 * dialog is hidden.
 */
data class ElevatedAccessSnapshot(
    val title: String,
    val message: String,
    val passwordValue: String,
    val passwordRevision: Int,
    val passwordError: String?,
    val passwordHint: String?,
    val hasBiometric: Boolean,
    val biometricEnabled: Boolean,
    val hasYubiKey: Boolean,
    val yubiKeyEnabled: Boolean,
    val confirmEnabled: Boolean,
    val isLoading: Boolean,
)

/** One breached service in an [EmailLeakSnapshot] / [WebsiteLeakSnapshot]. [descriptionText] is HTML. */
data class LeakBreachSnapshot(
    val title: String,
    val domain: String,
    // Not `description`: that collides with NSObject.description (non-optional) on Swift.
    val descriptionText: String,
    val icon: String?,
    val count: Int?,
    /** The localized, number-formatted "Found in N account(s)" line. */
    val countText: String?,
    val occurredAt: String?,
    val reportedAt: String?,
    val dataClasses: List<String>,
)

/**
 * The email / username breach dialog. [errorText] is set when the check failed; otherwise [breaches] may be
 * empty. `null` from [KeyguardCore.observeEmailLeak] means the dialog is hidden.
 */
data class EmailLeakSnapshot(
    val title: String,
    val note: String,
    val poweredBy: String,
    val isLoading: Boolean,
    val breaches: List<LeakBreachSnapshot> = emptyList(),
    val errorText: String? = null,
    val breachFoundTitle: String,
    val breachNotFoundTitle: String,
    val breachSectionTitle: String,
)

/**
 * The password breach dialog. [occurrences] is `null` when the check failed; [errorText] is then set.
 * `null` from [KeyguardCore.observePasswordLeak] means the dialog is hidden.
 */
data class PasswordLeakSnapshot(
    val title: String,
    val note: String,
    val poweredBy: String,
    val isLoading: Boolean,
    val occurrences: Int? = null,
    /** The localized, number-formatted "Seen N time(s)" counter. */
    val occurrencesText: String? = null,
    val errorText: String? = null,
    val occurrencesFoundTitle: String,
    val occurrencesFoundText: String,
    val occurrencesNotFoundTitle: String,
)

/**
 * The website breach dialog. It has no error state: the producer falls back to an empty [breaches] list.
 * `null` from [KeyguardCore.observeWebsiteLeak] means the dialog is hidden.
 */
data class WebsiteLeakSnapshot(
    val title: String,
    val note: String,
    val poweredBy: String,
    val isLoading: Boolean,
    val breaches: List<LeakBreachSnapshot> = emptyList(),
    val breachFoundTitle: String,
    val breachNotFoundTitle: String,
    val breachSectionTitle: String,
)

/**
 * The account color picker. Each of [items] carries an opaque id passed back via
 * [KeyguardCore.selectColorPickerSwatch]. [selectedIndex] is a [ColorSwatchSnapshot.index], or `-1` for none.
 * `null` from [KeyguardCore.observeColorPicker] means the dialog is hidden.
 */
data class ColorPickerSnapshot(
    val title: String,
    val items: List<ColorSwatchSnapshot>,
    val selectedIndex: Int,
    val confirmEnabled: Boolean,
)

/** One accent swatch; [argbLight] / [argbDark] are packed 0xAARRGGBB colors for the light / dark appearance. */
data class ColorSwatchSnapshot(
    val id: String,
    val index: Int,
    val argbLight: Long,
    val argbDark: Long,
)

/**
 * The read-only "Collection info" / "Organization info" dialog. [flags] are the capability lines ("Read only",
 * "Hide passwords", "Self-hosted"), English-only like the Compose dialog. `null` from
 * [KeyguardCore.observeInfoDialog] means the dialog is hidden.
 */
data class InfoDialogSnapshot(
    val title: String,
    val subtitle: String?,
    val flags: List<String>,
)

/**
 * The account picker ("Save to", "Copy to…") and the folder picker ("Move to folder"). Rows are selected via
 * [KeyguardCore.selectAccountPickerItem] with their [AccountPickerItemSnapshot.key]. [confirmEnabled] is false
 * while the producer's validation fails. `null` from [KeyguardCore.observeAccountPicker] means the dialog is
 * hidden.
 */
data class AccountPickerSnapshot(
    val newFolderName: String? = null,
    val newFolderNameRevision: Int = 0,
    val newFolderNameError: String? = null,
    val title: String,
    /** An informational note above the sections, e.g. why some accounts are missing. */
    val note: String? = null,
    val sections: List<AccountPickerSectionSnapshot>,
    val confirmEnabled: Boolean,
)

/** A group of [AccountPickerSnapshot] rows; [title] is an optional header, `null` for most sections. */
data class AccountPickerSectionSnapshot(
    val title: String?,
    val items: List<AccountPickerItemSnapshot>,
)

/** [enabled] gates the tap; a tap re-runs the producer, which re-emits the refreshed [selected] state. */
data class AccountPickerItemSnapshot(
    val key: String,
    val title: String,
    val text: String?,
    val selected: Boolean,
    val enabled: Boolean,
)

internal fun AnnotatedString.toAttachmentPreviewSpans(): List<AttachmentPreviewSpanSnapshot> =
    spanStyles.mapNotNull { range ->
        val color = range.item.color
            .takeIf { it.isSpecified }
            ?.toArgb()
        val bold = range.item.fontWeight == FontWeight.Bold
        if (color == null && !bold) {
            return@mapNotNull null
        }
        AttachmentPreviewSpanSnapshot(
            start = range.start,
            end = range.end,
            colorArgb = color?.toArgbLong() ?: 0L,
            bold = bold,
        )
    }


/** Searchable, account-scoped link targets. */
data class CipherLinkPickerSnapshot(
    val query: String,
    val queryRevision: Int,
    val items: List<CipherLinkPickerItemSnapshot>,
)

data class CipherLinkPickerItemSnapshot(
    val id: String,
    val title: String,
    val text: String?,
)

/** User-entered attempt only; the expected password stays inside the shared producer. */
data class PasswordMemorySnapshot(
    val value: String,
    val revision: Int,
    val error: String?,
    val canVerify: Boolean,
)
