package com.artemchep.keyguard.apple.dialog

import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.artemchep.keyguard.pick
import com.artemchep.keyguard.common.model.BarcodeImageFormat
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewState
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeState
import com.artemchep.keyguard.feature.largetype.LargeTypeState
import com.artemchep.keyguard.apple.KeyguardCore
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.format
import kotlinx.coroutines.flow.mapNotNull
import platform.Foundation.NSData

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.largetype.LargeTypeState] for the SwiftUI
 * "Show in Large Type" dialog. [groups] are the phrase groups, each a list of
 * code-point tiles; a tile is highlighted while its [LargeTypeSymbolSnapshot.index]
 * is `<= ` [selectedIndex]. [note] is the optional composite-Unicode warning. Built
 * by [KeyguardCore.buildLargeTypeSnapshot]; `null` from [KeyguardCore.observeLargeType]
 * means the dialog is hidden.
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

/** The colorize bucket of a [LargeTypeSymbolSnapshot] tile. */
enum class LargeTypeSymbolColor {
    PLAIN,
    DIGIT,
    SYMBOL,
}

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.barcodetype.BarcodeTypeState] for the SwiftUI
 * "Show as Barcode" dialog. SwiftUI renders the bitmap natively from [data] +
 * [format] (the [com.artemchep.keyguard.common.model.BarcodeImageFormat] name);
 * [formatTitle] is the human-readable selected format and [options] the format
 * dropdown (each carries an opaque id passed back via
 * [KeyguardCore.selectBarcodeFormat]). [note] is the optional caption shown below
 * the barcode; [formatSelectable] is false when the entry point pins the format
 * (e.g. a TOTP QR code). Built by [KeyguardCore.buildBarcodeSnapshot]; `null` from
 * [KeyguardCore.observeBarcode] means the dialog is hidden.
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

/** One entry in the "Show as Barcode" format dropdown. */
data class BarcodeFormatOptionSnapshot(
    val id: String,
    val title: String,
    val selected: Boolean,
)

/**
 * A flat projection of the passkey credential detail dialog, mirroring the
 * fields of the shared Compose dialog. When [error] is set the remaining
 * fields are absent. Built by [KeyguardCore.buildPasskeyCredentialSnapshot];
 * `null` from [KeyguardCore.observePasskeyCredential] means the dialog is
 * hidden. [canUse] surfaces the producer's onUse closure (pick-passkey app
 * mode only) behind [KeyguardCore.usePasskeyCredential].
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

/** What an [AttachmentPreviewSnapshot] is currently showing. */
enum class AttachmentPreviewKindSnapshot {
    LOADING,
    IMAGE,
    TEXT,
    MARKDOWN,
    ERROR,
}

/**
 * One syntax-highlight run over [AttachmentPreviewSnapshot.text], projected
 * from the shared producer's Compose AnnotatedString span styles so SwiftUI
 * can rebuild the equivalent AttributedString. [colorArgb] is `0` when the
 * run only changes the weight (the highlighter always emits opaque colors,
 * so `0` is never a real value).
 */
data class AttachmentPreviewSpanSnapshot(
    val start: Int,
    val end: Int,
    val colorArgb: Long,
    val bold: Boolean,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewState]
 * for the SwiftUI attachment preview sheet. Exactly one of the content fields
 * matters depending on [kind]: [imageData] for IMAGE (raw decrypted bytes,
 * crossing as NSData so Swift can feed NSImage(data:) without per-byte objc
 * calls), [text] + [spans] for TEXT / MARKDOWN, [errorMessage] for ERROR.
 * [canCopy] surfaces the producer's copy-all closure behind
 * [KeyguardCore.invokeAttachmentPreviewCopy]. Built by
 * [KeyguardCore.buildAttachmentPreviewSnapshot]; `null` from
 * [KeyguardCore.observeAttachmentPreview] means the dialog is hidden.
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
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.confirmation.ConfirmationState] for the SwiftUI
 * confirmation dialog. This is the generic dialog every cipher action routes
 * through (rename, change password, trash / delete, "Configure Watchtower alerts",
 * the various pickers) via a [com.artemchep.keyguard.feature.confirmation.ConfirmationRoute]
 * navigation intent caught by [DialogController.navigationInterceptor]. [items] are
 * rendered natively; [confirmEnabled] mirrors `state.onConfirm != null` (the shared
 * producer's validation). [docUrl] is the dialog's documentation link. Built by
 * [DialogController]; `null` from [KeyguardCore.observeConfirmation] means the
 * dialog is hidden.
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
 * Which kind of input a [ConfirmationItemSnapshot] row is. ([CHOICE] is the
 * single-select dropdown — named CHOICE rather than ENUM because the latter
 * lowercases to the Swift keyword `enum` when exported.)
 */
enum class ConfirmationItemKind {
    BOOLEAN,
    STRING,
    CHOICE,
    FILE,
}

/**
 * One row of a [ConfirmationSnapshot], a flat projection of the shared
 * `ConfirmationState.Item` sealed type discriminated by [kind] (the same
 * kind-enum + nullable-fields shape as [AttachmentPreviewSnapshot]). Only the
 * fields relevant to [kind] are populated; SwiftUI switches on [kind] and pushes
 * mutations back through `KeyguardCore` keyed by [key].
 */
data class ConfirmationItemSnapshot(
    val removable: Boolean = false,
    val key: String,
    val kind: ConfirmationItemKind,
    val enabled: Boolean,
    val title: String,
    // BOOLEAN (+ shared subtitle text)
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
    // ENUM
    val enumValue: String = "",
    val options: List<ConfirmationEnumOptionSnapshot> = emptyList(),
    val docText: String? = null,
    /** The selected option's doc has a "learn more" link; open it with its [key]. */
    val docHasLink: Boolean = false,
    // FILE
    val fileName: String? = null,
    val hasFile: Boolean = false,
)

/** One option of a [ConfirmationItemKind.ENUM] row's dropdown. */
data class ConfirmationEnumOptionSnapshot(
    val key: String,
    val title: String,
    val text: String?,
    val selected: Boolean,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessState]
 * for the SwiftUI master-password re-prompt dialog. A reprompt-protected cipher's
 * copy / reveal / edit action fires an
 * [com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessRoute]
 * navigation intent caught by [DialogController.navigationInterceptor]. SwiftUI
 * renders the [message], the secure [passwordValue] field (writes back through
 * [com.artemchep.keyguard.apple.KeyguardCore.setElevatedAccessPassword]), and — when
 * present — Touch ID / Face ID ([hasBiometric]) and YubiKey ([hasYubiKey]) shortcuts.
 * [confirmEnabled] mirrors `state.onConfirm != null` (the producer's validation; a
 * wrong password surfaces as a toast, not an inline error). Built by
 * [DialogController]; `null` from
 * [com.artemchep.keyguard.apple.KeyguardCore.observeElevatedAccess] means the dialog
 * is hidden.
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

/**
 * One breached service in an [EmailLeakSnapshot] / [WebsiteLeakSnapshot], a flat
 * projection of the shared
 * [com.artemchep.keyguard.feature.emailleak.EmailLeakState.Breach] /
 * [com.artemchep.keyguard.feature.websiteleak.WebsiteLeakState.Breach] (both have
 * the identical shape). SwiftUI renders [icon] as a favicon, [dataClasses] as chips,
 * [count] / [occurredAt] / [reportedAt] as the dated counters, and [description] as
 * HTML (rendered via the shared AttributedString conversion).
 */
data class LeakBreachSnapshot(
    val title: String,
    val domain: String,
    // Named descriptionText, not description: a Kotlin property exported as
    // `description` collides with NSObject.description (non-optional) on Swift
    // (the same gotcha as ConfirmationItemSnapshot).
    val descriptionText: String,
    val icon: String?,
    val count: Int?,
    // The localized, number-formatted "Found in N account(s)" line (built in the
    // controller because plural resolution + number formatting live in Kotlin).
    val countText: String?,
    val occurredAt: String?,
    val reportedAt: String?,
    val dataClasses: List<String>,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.emailleak.EmailLeakState] for the SwiftUI
 * email / username breach dialog. A cipher / account field's "Check data breaches"
 * action fires an
 * [com.artemchep.keyguard.feature.emailleak.EmailLeakRoute] navigation intent caught
 * by [DialogController.navigationInterceptor]. While [isLoading] the dialog shows a
 * skeleton; on success [breaches] is the (possibly empty) list of breached services;
 * on failure [errorText] carries the already-localized failure note. Built by
 * [DialogController]; `null` from
 * [com.artemchep.keyguard.apple.KeyguardCore.observeEmailLeak] means the dialog is hidden.
 */
data class EmailLeakSnapshot(
    val title: String,
    val note: String,
    val poweredBy: String,
    val isLoading: Boolean,
    val breaches: List<LeakBreachSnapshot> = emptyList(),
    val errorText: String? = null,
    // The "Pwned!" / "No breaches found" header titles, pre-resolved.
    val breachFoundTitle: String,
    val breachNotFoundTitle: String,
    val breachSectionTitle: String,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.passwordleak.PasswordLeakState] for the SwiftUI
 * password breach dialog. A cipher / generator-history password's "Check data
 * breaches" action fires a
 * [com.artemchep.keyguard.feature.passwordleak.PasswordLeakRoute] navigation intent
 * caught by [DialogController.navigationInterceptor]. While [isLoading] the dialog
 * shows a skeleton; on success [occurrences] is the breach count (null means the
 * check failed and [errorText] is shown). Built by [DialogController]; `null` from
 * [com.artemchep.keyguard.apple.KeyguardCore.observePasswordLeak] means the dialog
 * is hidden.
 */
data class PasswordLeakSnapshot(
    val title: String,
    val note: String,
    val poweredBy: String,
    val isLoading: Boolean,
    val occurrences: Int? = null,
    // The localized, number-formatted "Seen N time(s)" counter, built in the
    // controller (plural resolution + number formatting live in Kotlin).
    val occurrencesText: String? = null,
    val errorText: String? = null,
    // The "Compromised password" header title + subtitle, and the all-clear title.
    val occurrencesFoundTitle: String,
    val occurrencesFoundText: String,
    val occurrencesNotFoundTitle: String,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.websiteleak.WebsiteLeakState] for the SwiftUI
 * website breach dialog. A cipher URI's "Check data breaches" action fires a
 * [com.artemchep.keyguard.feature.websiteleak.WebsiteLeakRoute] navigation intent
 * caught by [DialogController.navigationInterceptor]. The website producer never
 * surfaces an error (it falls back to an empty list), so there is no [EmailLeakSnapshot.errorText]
 * counterpart. Built by [DialogController]; `null` from
 * [com.artemchep.keyguard.apple.KeyguardCore.observeWebsiteLeak] means the dialog is hidden.
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
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.colorpicker.ColorPickerState] for the SwiftUI
 * color picker dialog. The account detail's "Change color" action fires a
 * [com.artemchep.keyguard.feature.colorpicker.ColorPickerRoute] navigation intent
 * caught by [DialogController.navigationInterceptor]. [items] are the selectable
 * accent swatches (each carries an opaque [ColorSwatchSnapshot.id] passed back via
 * [com.artemchep.keyguard.apple.KeyguardCore.selectColorPickerSwatch]); [selectedIndex]
 * mirrors the producer's chosen index (-1 = none). [confirmEnabled] mirrors
 * `state.onConfirm != null`. Built by [DialogController]; `null` from
 * [com.artemchep.keyguard.apple.KeyguardCore.observeColorPicker] means the dialog is hidden.
 */
data class ColorPickerSnapshot(
    val title: String,
    val items: List<ColorSwatchSnapshot>,
    val selectedIndex: Int,
    val confirmEnabled: Boolean,
)

/**
 * One selectable accent swatch in a [ColorPickerSnapshot]. [argbLight] / [argbDark]
 * are the packed ARGB colors (from Compose's `Color.toArgb()`) for the two
 * appearances, so SwiftUI renders the swatch matching the current color scheme.
 */
data class ColorSwatchSnapshot(
    val id: String,
    val index: Int,
    val argbLight: Long,
    val argbDark: Long,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.home.vault.collection.CollectionState] /
 * [com.artemchep.keyguard.feature.home.vault.organization.OrganizationState] content
 * for the SwiftUI read-only "Collection info" / "Organization info" dialog. A
 * collection / organization row's "Info" action fires a
 * [com.artemchep.keyguard.feature.home.vault.collection.CollectionRoute] /
 * [com.artemchep.keyguard.feature.home.vault.organization.OrganizationRoute] navigation
 * intent caught by [DialogController.navigationInterceptor]. [flags] are the
 * already-localized capability lines ("Read only", "Hide passwords", "Self-hosted"),
 * mirroring the Compose dialog's `ExpandedIfNotEmpty` rows. Built by [DialogController];
 * `null` from [com.artemchep.keyguard.apple.KeyguardCore.observeInfoDialog] means the
 * dialog is hidden.
 */
data class InfoDialogSnapshot(
    val title: String,
    val subtitle: String?,
    val flags: List<String>,
)

/**
 * A flat projection of the shared
 * [com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationState]
 * for the SwiftUI account picker. The add form's ownership "Save to" row fires an
 * [com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute]
 * navigation intent caught by [DialogController.navigationInterceptor]. For the Send
 * form only the account section is shown (organization / collection / folder are
 * hidden via the route flags); the cipher form additionally surfaces them. SwiftUI
 * renders the sections, taps route back via [DialogController.selectAccountPickerItem]
 * keyed by [AccountPickerItemSnapshot.key], and confirm / deny finish through
 * [DialogController.confirmAccountPicker] / [DialogController.denyAccountPicker].
 * [confirmEnabled] mirrors `state.onConfirm != null` (the producer's validation).
 */
data class AccountPickerSnapshot(
    val newFolderName: String? = null,
    val newFolderNameRevision: Int = 0,
    val newFolderNameError: String? = null,
    val title: String,
    val sections: List<AccountPickerSectionSnapshot>,
    val confirmEnabled: Boolean,
)

/** One titled group of [AccountPickerSnapshot] choices (accounts / organizations / …). */
data class AccountPickerSectionSnapshot(
    val title: String?,
    val items: List<AccountPickerItemSnapshot>,
)

/**
 * One selectable row of an [AccountPickerSectionSnapshot]: an account / organization /
 * collection / folder. [key] routes the tap back; [enabled] gates it (a tap re-runs the
 * producer, which re-emits the refreshed selection).
 */
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
            colorArgb = color?.toLong()?.and(ARGB_MASK) ?: 0L,
            bold = bold,
        )
    }


/** Searchable, account-scoped link targets from the shared picker producer. */
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

private const val ARGB_MASK = 0xFFFFFFFFL
