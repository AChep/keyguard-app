package com.artemchep.keyguard.apple.dialog

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkPickerRoute
import com.artemchep.keyguard.feature.home.vault.link.CipherLinkPickerResult
import com.artemchep.keyguard.feature.home.vault.link.cipherLinkPickerStateProducer

import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.folder.FolderConfirmationResult
import com.artemchep.keyguard.feature.confirmation.folder.folderConfirmationStateProducer
import com.artemchep.keyguard.feature.confirmation.tags.TagsConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.tags.TagsConfirmationResult
import com.artemchep.keyguard.feature.confirmation.tags.tagsConfirmationStateProducer
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.main
import com.artemchep.keyguard.pick
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.BiometricAuthPromptSimple
import com.artemchep.keyguard.common.model.LockReason
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.common.model.YubiKeyAuthPrompt
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.exception.HttpException
import com.artemchep.keyguard.common.usecase.GetBarcodeUsageHistory
import com.artemchep.keyguard.common.usecase.ClearVaultSession
import com.artemchep.keyguard.common.usecase.NumberFormatter
import com.artemchep.keyguard.common.usecase.PutBarcodeUsageHistory
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewContent
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewError
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewRoute
import com.artemchep.keyguard.feature.attachmentpreview.AttachmentPreviewState
import com.artemchep.keyguard.feature.attachmentpreview.attachmentPreviewStateProducer
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeRoute
import com.artemchep.keyguard.feature.barcodetype.BarcodeTypeState
import com.artemchep.keyguard.feature.barcodetype.barcodeTypeStateProducer
import com.artemchep.keyguard.feature.colorpicker.ColorPickerResult
import com.artemchep.keyguard.feature.colorpicker.ColorPickerRoute
import com.artemchep.keyguard.feature.colorpicker.colorPickerStateProducer
import com.artemchep.keyguard.feature.home.vault.collection.CollectionRoute
import com.artemchep.keyguard.feature.home.vault.collection.CollectionState
import com.artemchep.keyguard.feature.home.vault.collection.collectionScreenStateProducer
import com.artemchep.keyguard.feature.home.vault.organization.OrganizationRoute
import com.artemchep.keyguard.feature.home.vault.organization.OrganizationState
import com.artemchep.keyguard.feature.home.vault.organization.organizationScreenStateProducer
import com.artemchep.keyguard.feature.confirmation.ConfirmationResult
import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.ConfirmationState
import com.artemchep.keyguard.feature.confirmation.confirmationStateProducer
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessResult
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessRoute
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.ElevatedAccessState
import com.artemchep.keyguard.feature.confirmation.elevatedaccess.elevatedAccessStateProducer
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationResult
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationRoute
import com.artemchep.keyguard.feature.confirmation.organization.OrganizationConfirmationState
import com.artemchep.keyguard.feature.confirmation.organization.organizationConfirmationStateProducer
import com.artemchep.keyguard.feature.emailleak.EmailLeakRoute
import com.artemchep.keyguard.feature.emailleak.EmailLeakState
import com.artemchep.keyguard.feature.emailleak.emailLeakStateProducer
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.feature.justdeleteme.directory.JustDeleteMeServiceViewDialogRoute
import com.artemchep.keyguard.feature.justgetdata.directory.JustGetMyDataViewDialogRoute
import com.artemchep.keyguard.feature.passwordmemory.PasswordMemoryRoute
import com.artemchep.keyguard.feature.passwordmemory.PasswordMemoryState
import com.artemchep.keyguard.feature.passwordmemory.passwordMemoryStateProducer
import com.artemchep.keyguard.feature.largetype.LargeTypeRoute
import com.artemchep.keyguard.feature.largetype.LargeTypeState
import com.artemchep.keyguard.feature.largetype.largeTypeStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewRoute
import com.artemchep.keyguard.feature.passkeys.PasskeysCredentialViewState
import com.artemchep.keyguard.feature.passkeys.directory.PasskeysServiceViewDialogRoute
import com.artemchep.keyguard.feature.passkeys.passkeysCredentialViewStateProducer
import com.artemchep.keyguard.feature.passwordleak.PasswordLeakRoute
import com.artemchep.keyguard.feature.passwordleak.PasswordLeakState
import com.artemchep.keyguard.feature.passwordleak.passwordLeakStateProducer
import com.artemchep.keyguard.feature.tfa.directory.TwoFaServiceViewDialogRoute
import com.artemchep.keyguard.feature.websiteleak.WebsiteLeakRoute
import com.artemchep.keyguard.feature.websiteleak.WebsiteLeakState
import com.artemchep.keyguard.feature.websiteleak.websiteLeakStateProducer
import com.artemchep.keyguard.apple.add.AddFilePickerKind
import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.directory.DirectoryLinkTitles
import com.artemchep.keyguard.apple.directory.ServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.directory.directoryLinkTitles
import com.artemchep.keyguard.apple.directory.toServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.util.io.toNSData
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.res.elevatedaccess_biometric_auth_confirm_title
import com.artemchep.keyguard.res.Res
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.scope.Scope

/**
 * The SwiftUI-presented dialog subsystem of the macOS bridge: the "Show in
 * Large Type", "Show as Barcode", passkey credential detail and attachment
 * preview sheets.
 *
 * Each is a [DialogHost] channel fed by the shared headless producers. The
 * detail screens (cipher / send / account / password history) don't present
 * these themselves — they hand [navigationInterceptor] to their producer's
 * [com.artemchep.keyguard.feature.navigation.NavigationController], which
 * catches the matching navigation route and turns it into a [DialogHost.present]
 * call on this controller.
 */
internal class DialogController(
    private val ctx: CoreContext,
    private val authPromptHost: AuthPromptHost,
) {
    /**
     * Shared plumbing for the SwiftUI-presented dialogs: a registered Swift sink
     * that receives `null` while hidden, at most one running headless producer
     * job, and the per-presentation [handlers] backing the dialog's Swift-facing
     * invoke / select methods. All fields are main-confined — [register],
     * [present] and [close] are called on the main thread (the dialog navigation
     * interceptor hops before presenting) — while the producer launched by
     * [present] runs on [CoreContext.backgroundScope] and delivers through its
     * `publish` parameter, which installs the handlers and pushes the snapshot
     * on the main thread.
     */
    private inner class DialogHost<S : Any, H>(
        private val noHandlers: H,
    ) {
        private var sink: ((S?) -> Unit)? = null
        private var job: Job? = null

        var handlers: H = noHandlers
            private set

        /** Registers the SwiftUI sink; emits `null` (hidden) right away. */
        fun register(onChange: (S?) -> Unit): KeyguardCancellable {
            sink = onChange
            val job = ctx.scope.launch { onChange(null) }
            return KeyguardCancellable(job)
        }

        /**
         * Starts a presentation: tears the previous producer down and runs
         * [block] on [CoreContext.backgroundScope]. [block] delivers every
         * snapshot — and the handlers backing the snapshot's ids — through its
         * `publish` parameter. No-op while no sink is registered.
         */
        fun present(
            block: suspend CoroutineScope.(publish: suspend (S, H) -> Unit) -> Unit,
        ) {
            if (sink == null) return
            job?.cancel()
            handlers = noHandlers
            job = ctx.backgroundScope.launch {
                block { snapshot, newHandlers ->
                    ctx.publishOnMain {
                        handlers = newHandlers
                        sink?.invoke(snapshot)
                    }
                }
            }
        }

        /** Dismisses the dialog and tears the headless producer down. */
        fun close() {
            job?.cancel()
            job = null
            handlers = noHandlers
            sink?.invoke(null)
        }
    }

    /**
     * The "Show in Large Type" dialog channel: a detail producer's
     * [LargeTypeRoute] navigation intent is caught by [navigationInterceptor]
     * and turned into a [presentLargeType] call. The handlers map a tile index
     * to the producer's select closure so [selectLargeTypeSymbol] only ever
     * passes an index.
     */
    private val largeTypeDialog =
        DialogHost<LargeTypeSnapshot, Map<Int, () -> Unit>>(emptyMap())

    /**
     * The "Show as Barcode" dialog channel: a detail producer's
     * [BarcodeTypeRoute] navigation intent is caught by [navigationInterceptor]
     * and turned into a [presentBarcode] call. The handlers map a format-option
     * id to the producer's select closure so [selectBarcodeFormat] only ever
     * passes an opaque id.
     */
    private val barcodeDialog =
        DialogHost<BarcodeSnapshot, Map<String, () -> Unit>>(emptyMap())

    /**
     * The passkey credential detail dialog channel: a detail producer's
     * [PasskeysCredentialViewRoute] navigation intent (a passkey row click) is
     * caught by [navigationInterceptor] and turned into a
     * [presentPasskeyCredential] call. The handler is the producer's onUse
     * closure (only present in the pick-passkey app mode), behind
     * [usePasskeyCredential].
     */
    private val passkeyCredentialDialog =
        DialogHost<PasskeyCredentialSnapshot, (() -> Unit)?>(null)

    /**
     * The attachment preview dialog channel: clicking a previewable attachment
     * row fires the shared producer's [AttachmentPreviewRoute] navigation
     * intent, caught by [navigationInterceptor] and turned into a
     * [presentAttachmentPreview] call. The handler is the text content's
     * copy-all closure behind [invokeAttachmentPreviewCopy].
     */
    private val attachmentPreviewDialog =
        DialogHost<AttachmentPreviewSnapshot, (() -> Unit)?>(null)

    /**
     * The per-presentation closures backing the confirmation dialog's Swift-facing
     * mutators, refreshed on every producer emission (the maps are keyed by the
     * snapshot item / option keys SwiftUI passes back).
     */
    private class ConfirmationHandlers(
        val onAdd: (() -> Unit)? = null,
        val onRemove: Map<String, () -> Unit> = emptyMap(),
        val booleanOnChange: Map<String, (Boolean) -> Unit> = emptyMap(),
        val stringOnChange: Map<String, (String) -> Unit> = emptyMap(),
        // itemKey -> (optionKey -> onClick)
        val enumOnClick: Map<String, Map<String, () -> Unit>> = emptyMap(),
        val fileOnSelect: Map<String, () -> Unit> = emptyMap(),
        val fileOnClear: Map<String, () -> Unit> = emptyMap(),
        // itemKey -> the selected option's "learn more" link
        val docOnLearnMore: Map<String, () -> Unit> = emptyMap(),
        val onConfirm: (() -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
    )

    /**
     * The generic confirmation dialog channel: every cipher action that asks for
     * confirmation (rename, change password, trash / delete, "Configure Watchtower
     * alerts", the various pickers) fires a [ConfirmationRoute] navigation intent,
     * caught by [navigationInterceptor] and turned into a [presentConfirmation]
     * call running the shared [confirmationStateProducer] headlessly. The handlers
     * back the Swift-facing item mutators and confirm / deny closures.
     */
    private val confirmationDialog =
        DialogHost<ConfirmationSnapshot, ConfirmationHandlers>(ConfirmationHandlers())

    /**
     * Opens a link a dialog producer emits ([NavigationIntent.NavigateToBrowser]).
     * Late-bound by [KeyguardCore] to the navigation stack's open-url handler.
     */
    var openUrl: (String) -> Unit = {}

    /**
     * The per-presentation closures backing the master-password re-prompt dialog's
     * Swift-facing mutators, refreshed on every producer emission.
     */
    private class ElevatedAccessHandlers(
        val passwordOnChange: ((String) -> Unit)? = null,
        val onBiometric: (() -> Unit)? = null,
        val onYubiKey: (() -> Unit)? = null,
        val onConfirm: (() -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
    )

    /**
     * The master-password re-prompt ("elevated access") dialog channel: a
     * reprompt-protected cipher's copy / reveal / edit action fires an
     * [ElevatedAccessRoute] navigation intent (wrapped in a
     * [com.artemchep.keyguard.feature.navigation.RouteResultReceiver]), caught by
     * [navigationInterceptor] and turned into a [presentElevatedAccess] call running
     * the shared [elevatedAccessStateProducer] headlessly. The producer also emits
     * biometric / YubiKey side-effects, which this channel routes through the shared
     * [AuthPromptHost] (the same native Touch ID / YubiKey path as the unlock screen).
     */
    private val elevatedAccessDialog =
        DialogHost<ElevatedAccessSnapshot, ElevatedAccessHandlers>(ElevatedAccessHandlers())

    /**
     * The service-info dialog channel: the cipher detail's "Inactive one-time
     * password" / "Inactive passkey" rows fire a [TwoFaServiceViewDialogRoute] /
     * [PasskeysServiceViewDialogRoute] navigation intent, caught by
     * [navigationInterceptor] and turned into a [presentServiceInfo] call. The
     * route args already carry the full service model, so this is a single static
     * snapshot — no producer, no handlers (the dialog only renders + closes).
     */
    private val serviceInfoDialog =
        DialogHost<ServiceDirectoryDetailSnapshot, Unit>(Unit)

    /**
     * The email / username breach ("Have I Been Pwned") dialog channel: a cipher /
     * account field's "Check data breaches" action fires an [EmailLeakRoute]
     * navigation intent, caught by [navigationInterceptor] and turned into a
     * [presentEmailLeak] call running the shared [emailLeakStateProducer]
     * headlessly. The dialog only renders + closes, so there are no handlers.
     */
    private val emailLeakDialog =
        DialogHost<EmailLeakSnapshot, Unit>(Unit)

    /**
     * The password breach dialog channel: a cipher / generator-history password's
     * "Check data breaches" action fires a [PasswordLeakRoute] navigation intent,
     * caught by [navigationInterceptor] and turned into a [presentPasswordLeak] call
     * running the shared [passwordLeakStateProducer] headlessly. No handlers.
     */
    private val passwordLeakDialog =
        DialogHost<PasswordLeakSnapshot, Unit>(Unit)

    /**
     * The website breach dialog channel: a cipher URI's "Check data breaches" action
     * fires a [WebsiteLeakRoute] navigation intent, caught by [navigationInterceptor]
     * and turned into a [presentWebsiteLeak] call running the shared
     * [websiteLeakStateProducer] headlessly. No handlers.
     */
    private val websiteLeakDialog =
        DialogHost<WebsiteLeakSnapshot, Unit>(Unit)

    /**
     * The color picker dialog channel: the account detail's "Change color" action
     * fires a [ColorPickerRoute] navigation intent (wrapped in a
     * [com.artemchep.keyguard.feature.navigation.RouteResultReceiver]), caught by
     * [navigationInterceptor] and turned into a [presentColorPicker] call running the
     * shared [produceColorPickerState] headlessly. The handlers map a swatch id to the
     * producer's per-color select closure plus the confirm / deny closures.
     */
    private val colorPickerDialog =
        DialogHost<ColorPickerSnapshot, ColorPickerHandlers>(ColorPickerHandlers())

    /**
     * The per-presentation closures backing the color picker dialog's Swift-facing
     * mutators, refreshed on every producer emission.
     */
    private class ColorPickerHandlers(
        // swatchId -> select closure (highlights the swatch in the producer)
        val onSelect: Map<String, () -> Unit> = emptyMap(),
        val onConfirm: (() -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
    )

    /**
     * The collection / organization "info" dialog channel: a collection / organization
     * row's "Info" action fires a [CollectionRoute] / [OrganizationRoute] navigation
     * intent, caught by [navigationInterceptor] and turned into a [presentCollectionInfo] /
     * [presentOrganizationInfo] call running the shared [collectionScreenState] /
     * [organizationScreenState] headlessly. The dialog only renders + closes, so there
     * are no handlers.
     */
    private val infoDialog =
        DialogHost<InfoDialogSnapshot, Unit>(Unit)

    /**
     * The per-presentation closures backing the account picker dialog's Swift-facing
     * mutators, refreshed on every producer emission.
     */
    private class AccountPickerHandlers(
        val onNewFolderName: ((String) -> Unit)? = null,
        // itemKey -> select closure (re-runs the producer with the new selection)
        val onSelect: Map<String, () -> Unit> = emptyMap(),
        val onConfirm: (() -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
    )

    /**
     * The account picker dialog channel: the add form's ownership "Save to" row fires
     * an [OrganizationConfirmationRoute] navigation intent (wrapped in a
     * [com.artemchep.keyguard.feature.navigation.RouteResultReceiver]), caught by
     * [navigationInterceptor] and turned into a [presentAccountPicker] call running
     * the shared [organizationConfirmationStateProducer] headlessly. The handlers map
     * a row key to the producer's per-item select closure plus the confirm / deny
     * closures.
     */
    private val accountPickerDialog =
        DialogHost<AccountPickerSnapshot, AccountPickerHandlers>(AccountPickerHandlers())

    private data class CipherLinkPickerHandlers(
        val onQuery: ((String) -> Unit)? = null,
        val onSelect: Map<String, () -> Unit> = emptyMap(),
        val onDeny: (() -> Unit)? = null,
    )

    private val cipherLinkPickerDialog =
        DialogHost<CipherLinkPickerSnapshot, CipherLinkPickerHandlers>(CipherLinkPickerHandlers())

    // The confirmation dialog's file-picker bridge (a FileItem row's "choose file"
    // action): the shared producer emits a FilePickerIntent through its
    // sideEffects flow; translate it to an AddFilePickerRequest (reusing the
    // create-form file-picker request type + Swift NSOpenPanel / .fileImporter
    // presentation) and feed the chosen file back into the producer continuation.
    private var onConfirmationFilePickerRequest: ((AddFilePickerRequest) -> Unit)? = null
    private val confirmationFilePickerHandlers = LinkedHashMap<String, (FilePickerResult?) -> Unit>()
    private var confirmationFilePickerRequestCounter = 0

    /**
     * A navigation interceptor for the detail producers: catches the dialog routes
     * that SwiftUI presents itself — the [LargeTypeRoute] push (and the mobile-only
     * [NavigationIntent.NavigateToLargeType]) and the [BarcodeTypeRoute] push — and
     * presents the matching dialog, swallowing the intent. Every other intent is
     * left unhandled (dropped, as before).
     */
    // [sessionKoin] is required to present the passkey credential dialog: its
    // producer resolves per-session use-cases (GetCiphers / PasskeyTargetCheck)
    // that the root DI does not bind. Call sites without passkey rows omit it.
    fun navigationInterceptor(
        sessionKoin: Scope? = null,
    ): (NavigationIntent) -> Boolean = { intent ->
        val passwordMemory = (intent as? NavigationIntent.NavigateToRoute)?.route as? PasswordMemoryRoute
        val largeTypeArgs = intent.toLargeTypeArgsOrNull()
        val barcodeArgs = intent.toBarcodeArgsOrNull()
        val passkeyCredentialArgs = intent.toPasskeyCredentialArgsOrNull()
        val attachmentPreviewArgs = intent.toAttachmentPreviewArgsOrNull()
        val confirmation = intent.toConfirmationOrNull()
        val tagsConfirmation = intent.toTagsConfirmationOrNull()
        val elevatedAccess = intent.toElevatedAccessOrNull()
        val serviceInfo = intent.toServiceInfoOrNull()
        val emailLeakArgs = intent.toEmailLeakArgsOrNull()
        val passwordLeakArgs = intent.toPasswordLeakArgsOrNull()
        val websiteLeakArgs = intent.toWebsiteLeakArgsOrNull()
        val colorPicker = intent.toColorPickerOrNull()
        val cipherLinkPicker = intent.toCipherLinkPickerOrNull()
        val accountPicker = intent.toAccountPickerOrNull()
        val folderPicker = intent.toFolderPickerOrNull()
        val collectionInfoArgs = intent.toCollectionInfoArgsOrNull()
        val organizationInfoArgs = intent.toOrganizationInfoArgsOrNull()
        // The producers dispatch their navigation intents from the background
        // pipeline, while the dialog state (the present* job + handler fields)
        // is main-confined — hop to the main scope before presenting.
        when {
            passwordMemory != null -> {
                ctx.scope.launch { presentPasswordMemory(passwordMemory.args) }
                true
            }
            cipherLinkPicker != null && sessionKoin != null -> {
                ctx.scope.launch {
                    presentCipherLinkPicker(cipherLinkPicker.first, cipherLinkPicker.second, sessionKoin)
                }
                true
            }

            largeTypeArgs != null -> {
                ctx.scope.launch {
                    presentLargeType(
                        largeTypeArgs,
                        lockVault = intent is NavigationIntent.NavigateToLargeType,
                    )
                }
                true
            }

            barcodeArgs != null -> {
                ctx.scope.launch { presentBarcode(barcodeArgs) }
                true
            }

            passkeyCredentialArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentPasskeyCredential(passkeyCredentialArgs, sessionKoin) }
                true
            }

            attachmentPreviewArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentAttachmentPreview(attachmentPreviewArgs, sessionKoin) }
                true
            }

            tagsConfirmation != null -> {
                ctx.scope.launch { presentTagsConfirmation(tagsConfirmation.first, tagsConfirmation.second) }
                true
            }

            confirmation != null -> {
                ctx.scope.launch { presentConfirmation(confirmation.first, confirmation.second) }
                true
            }

            elevatedAccess != null && sessionKoin != null -> {
                ctx.scope.launch { presentElevatedAccess(elevatedAccess, sessionKoin) }
                true
            }

            serviceInfo != null -> {
                ctx.scope.launch { presentServiceInfo(serviceInfo) }
                true
            }

            emailLeakArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentEmailLeak(emailLeakArgs, sessionKoin) }
                true
            }

            passwordLeakArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentPasswordLeak(passwordLeakArgs, sessionKoin) }
                true
            }

            websiteLeakArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentWebsiteLeak(websiteLeakArgs, sessionKoin) }
                true
            }

            colorPicker != null -> {
                ctx.scope.launch { presentColorPicker(colorPicker.first, colorPicker.second) }
                true
            }

            folderPicker != null && sessionKoin != null -> {
                ctx.scope.launch { presentFolderPicker(folderPicker.first, folderPicker.second, sessionKoin) }
                true
            }

            accountPicker != null && sessionKoin != null -> {
                ctx.scope.launch { presentAccountPicker(accountPicker.first, accountPicker.second, sessionKoin) }
                true
            }

            collectionInfoArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentCollectionInfo(collectionInfoArgs, sessionKoin) }
                true
            }

            organizationInfoArgs != null && sessionKoin != null -> {
                ctx.scope.launch { presentOrganizationInfo(organizationInfoArgs, sessionKoin) }
                true
            }

            else -> false
        }
    }

    private fun NavigationIntent.toLargeTypeArgsOrNull(): LargeTypeRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? LargeTypeRoute)?.args
        is NavigationIntent.NavigateToLargeType -> LargeTypeRoute.Args(
            phrases = phrases,
            colorize = colorize,
        )

        else -> null
    }

    private fun NavigationIntent.toBarcodeArgsOrNull(): BarcodeTypeRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? BarcodeTypeRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toPasskeyCredentialArgsOrNull(): PasskeysCredentialViewRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? PasskeysCredentialViewRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toAttachmentPreviewArgsOrNull(): AttachmentPreviewRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? AttachmentPreviewRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toEmailLeakArgsOrNull(): EmailLeakRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? EmailLeakRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toPasswordLeakArgsOrNull(): PasswordLeakRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? PasswordLeakRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toWebsiteLeakArgsOrNull(): WebsiteLeakRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? WebsiteLeakRoute)?.args
        else -> null
    }

    // The "Inactive one-time password" / "Inactive passkey" rows navigate to one of
    // these dialog routes; the args carry the full service model, projected here into
    // the same flat snapshot the directory detail screen renders. The projection
    // needs the localized link titles, which are resolved when the dialog presents.
    private fun NavigationIntent.toServiceInfoOrNull(): ((DirectoryLinkTitles) -> ServiceDirectoryDetailSnapshot)? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        return when (route) {
            is TwoFaServiceViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            is PasskeysServiceViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            // A login whose URL matches a known JustGetMyData / JustDeleteMe service:
            // the cipher detail's "Get my data" / "How to delete account" overflow
            // actions navigate to these dialog routes (args carry the full service
            // model), projected here into the same flat snapshot the directory detail
            // screen renders so the shared service-info dialog presents.
            is JustGetMyDataViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            is JustDeleteMeServiceViewDialogRoute -> { titles ->
                route.args.justDeleteMe.toServiceDirectoryDetailSnapshot(titles)
            }
            else -> null
        }
    }

    // A confirmation dialog route is always wrapped by registerRouteResultReceiver,
    // so the navigated route is an anonymous holder, not the ConfirmationRoute
    // itself — unwrap it through RouteResultReceiver to recover both the args and
    // the transmitter that delivers the result back to the registered receiver.
    private fun NavigationIntent.toCipherLinkPickerOrNull(): Pair<
        CipherLinkPickerRoute.Args,
        RouteResultTransmitter<CipherLinkPickerResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? CipherLinkPickerRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<CipherLinkPickerResult>)
    }

    private fun NavigationIntent.toConfirmationOrNull(): Pair<
        ConfirmationRoute.Args,
        RouteResultTransmitter<ConfirmationResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? ConfirmationRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<ConfirmationResult>)
    }

    private fun NavigationIntent.toTagsConfirmationOrNull(): Pair<
        TagsConfirmationRoute.Args,
        RouteResultTransmitter<TagsConfirmationResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? TagsConfirmationRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<TagsConfirmationResult>)
    }

    // The elevated-access dialog route is likewise wrapped by
    // registerRouteResultReceiver, so unwrap the holder to recover the transmitter
    // that delivers the Allow / Deny result back to the registered receiver (which
    // runs the original copy / reveal / edit once access is granted). The route
    // carries no args.
    private fun NavigationIntent.toElevatedAccessOrNull(): RouteResultTransmitter<ElevatedAccessResult>? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        holder.innerRoute as? ElevatedAccessRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return holder.resultTransmitter as RouteResultTransmitter<ElevatedAccessResult>
    }

    // The color-picker dialog route is likewise wrapped by registerRouteResultReceiver
    // (createColorPickerDialogIntent), so unwrap the holder to recover both the args
    // (the current accent color) and the transmitter that delivers the chosen color
    // back to the registered receiver (which persists it via PutAccountColorById).
    private fun NavigationIntent.toColorPickerOrNull(): Pair<
        ColorPickerRoute.Args,
        RouteResultTransmitter<ColorPickerResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? ColorPickerRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<ColorPickerResult>)
    }

    // The ownership "Save to" account picker route is wrapped by
    // registerRouteResultReceiver (see produceOwnershipFlow), so unwrap the holder to
    // recover both the args (the current account / flags) and the transmitter that
    // delivers the chosen ownership back to the registered receiver (which updates the
    // add form's ownership sink).
    private fun NavigationIntent.toAccountPickerOrNull(): Pair<
        OrganizationConfirmationRoute.Args,
        RouteResultTransmitter<OrganizationConfirmationResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? OrganizationConfirmationRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<OrganizationConfirmationResult>)
    }

    private fun NavigationIntent.toFolderPickerOrNull(): Pair<
        FolderConfirmationRoute.Args,
        RouteResultTransmitter<FolderConfirmationResult>,
    >? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        val holder = route as? RouteResultReceiver<*> ?: return null
        val inner = holder.innerRoute as? FolderConfirmationRoute ?: return null
        @Suppress("UNCHECKED_CAST")
        return inner.args to (holder.resultTransmitter as RouteResultTransmitter<FolderConfirmationResult>)
    }

    // The collection / organization "Info" rows navigate to a plain DialogRoute (not
    // wrapped in a result receiver), so recover the args directly off the route.
    private fun NavigationIntent.toCollectionInfoArgsOrNull(): CollectionRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? CollectionRoute)?.args
        else -> null
    }

    private fun NavigationIntent.toOrganizationInfoArgsOrNull(): OrganizationRoute.Args? = when (this) {
        is NavigationIntent.NavigateToRoute -> (route as? OrganizationRoute)?.args
        else -> null
    }

    /**
     * Registers the SwiftUI sink for the Large Type dialog. The callback receives
     * `null` while hidden and a [LargeTypeSnapshot] once a field's "Show in Large
     * Type" action fires. Mutate / dismiss via [selectLargeTypeSymbol] / [closeLargeType].
     */
    private val passwordMemoryDialog = DialogHost<PasswordMemorySnapshot, PasswordMemoryState?>(null)

    fun observePasswordMemory(onChange: (PasswordMemorySnapshot?) -> Unit): KeyguardCancellable =
        passwordMemoryDialog.register(onChange)

    fun setPasswordMemoryText(text: String) { passwordMemoryDialog.handlers?.password?.onChange?.invoke(text) }
    fun verifyPasswordMemory() { passwordMemoryDialog.handlers?.onVerify?.invoke() }
    fun closePasswordMemory() { passwordMemoryDialog.close() }

    private fun presentPasswordMemory(args: PasswordMemoryRoute.Args) {
        passwordMemoryDialog.present { publish ->
            val intercept: (NavigationIntent) -> Boolean = { intent ->
                if (intent is NavigationIntent.Pop || intent is NavigationIntent.PopById) {
                    ctx.scope.launch { closePasswordMemory() }
                    true
                } else false
            }
            ctx.koin.newHeadlessStateFlowScope("password_memory", this, intercept)
                .passwordMemoryStateProducer(args)
                .collect { state ->
                    publish(PasswordMemorySnapshot(
                        value = state.password.text,
                        revision = state.password.textRevision,
                        error = state.password.error,
                        canVerify = state.onVerify != null,
                    ), state)
                }
        }
    }

    fun observeLargeType(
        onChange: (LargeTypeSnapshot?) -> Unit,
    ): KeyguardCancellable = largeTypeDialog.register(onChange)

    /**
     * Runs the shared [largeTypeStateProducer] headlessly for [args] and projects
     * each emission into a [LargeTypeSnapshot]. Tears down any previously presented
     * instance first.
     */
    private fun presentLargeType(args: LargeTypeRoute.Args, lockVault: Boolean) {
        val leContext = ctx.koin.get<LeContext>()
        largeTypeDialog.present { publish ->
            var shouldLockVault = lockVault
            val title = textResource(Res.string.largetype_title, leContext)
            ctx.koin.newHeadlessStateFlowScope("largetype", this)
                .largeTypeStateProducer(args)
                .collect { loadable ->
                    val state = loadable.getOrNull() ?: return@collect
                    val selectHandlers = LinkedHashMap<Int, () -> Unit>()
                    state.groups.forEach { group ->
                        group.forEach { item ->
                            item.onClick?.let { selectHandlers[item.index] = it }
                        }
                    }
                    publish(buildLargeTypeSnapshot(title, state), selectHandlers)
                    // This dialog runs in the app scope, so its displayed text
                    // survives clearing the session behind it. Lock only once,
                    // after the first usable snapshot has reached the UI.
                    if (shouldLockVault) {
                        shouldLockVault = false
                        ctx.koin.get<ClearVaultSession>()(
                            LockReason.LOCK,
                            TextHolder.Res(Res.string.lock_reason_manually),
                        ).bind()
                    }
                }
        }
    }

    /**
     * Highlights every tile up to (and including) [index] by routing through the
     * producer's per-code-point select closure; the producer re-emits and the
     * refreshed [LargeTypeSnapshot] flows back to SwiftUI.
     */
    fun selectLargeTypeSymbol(index: Int) {
        largeTypeDialog.handlers[index]?.invoke()
    }

    /** Dismisses the Large Type dialog and tears the headless producer down. */
    fun closeLargeType() {
        largeTypeDialog.close()
    }

    private fun buildLargeTypeSnapshot(
        title: String,
        state: LargeTypeState,
    ): LargeTypeSnapshot = LargeTypeSnapshot(
        title = title,
        note = state.text,
        selectedIndex = state.index,
        groups = state.groups.map { group ->
            group.map { item ->
                LargeTypeSymbolSnapshot(
                    text = item.text,
                    index = item.index,
                    color = item.toSymbolColor(),
                )
            }
        },
    )

    // Mirrors the colorize decision in the Compose SymbolItem: colour only a
    // single-code-point tile, digits one way, symbols another, letters plain.
    private fun LargeTypeState.Item.toSymbolColor(): LargeTypeSymbolColor = when {
        !colorize || text.length > 1 -> LargeTypeSymbolColor.PLAIN
        text[0].isDigit() -> LargeTypeSymbolColor.DIGIT
        text[0].isLetter() -> LargeTypeSymbolColor.PLAIN
        else -> LargeTypeSymbolColor.SYMBOL
    }

    /**
     * Registers the SwiftUI sink for the "Show as Barcode" dialog. The callback
     * receives `null` while hidden and a [BarcodeSnapshot] once a field's "Show as
     * Barcode" action fires. Change format / dismiss via [selectBarcodeFormat] /
     * [closeBarcode].
     */
    fun observeBarcode(
        onChange: (BarcodeSnapshot?) -> Unit,
    ): KeyguardCancellable = barcodeDialog.register(onChange)

    /**
     * Runs the shared [barcodeTypeStateProducer] headlessly for [args] and projects
     * each emission into a [BarcodeSnapshot]. Tears down any previously presented
     * instance first. The barcode-usage-history use cases may be absent in the
     * macOS DI graph; the producer falls back to on-disk format persistence in
     * that case.
     */
    private fun presentBarcode(args: BarcodeTypeRoute.Args) {
        val leContext = ctx.koin.get<LeContext>()
        barcodeDialog.present { publish ->
            val title = textResource(Res.string.barcodetype_title, leContext)
            ctx.koin.newHeadlessStateFlowScope("barcodetype", this)
                .barcodeTypeStateProducer(
                    args = args,
                    getBarcodeUsageHistory = ctx.koin.getOrNull<GetBarcodeUsageHistory>(),
                    putBarcodeUsageHistory = ctx.koin.getOrNull<PutBarcodeUsageHistory>(),
                )
                .collect { loadable ->
                    val state = loadable.getOrNull() ?: return@collect
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val selectedTitle = state.format.format
                    val options = state.format.options.mapIndexed { index, action ->
                        val id = "fmt:$index"
                        action.onClick?.let { handlers[id] = it }
                        val optionTitle = textResource(action.title, leContext)
                        BarcodeFormatOptionSnapshot(
                            id = id,
                            title = optionTitle,
                            selected = optionTitle == selectedTitle,
                        )
                    }
                    publish(buildBarcodeSnapshot(title, state, options, args), handlers)
                }
        }
    }

    /**
     * Switches the rendered barcode format by routing through the producer's
     * per-format select closure; the producer re-emits and the refreshed
     * [BarcodeSnapshot] flows back to SwiftUI.
     */
    fun selectBarcodeFormat(id: String) {
        barcodeDialog.handlers[id]?.invoke()
    }

    /** Dismisses the "Show as Barcode" dialog and tears the headless producer down. */
    fun closeBarcode() {
        barcodeDialog.close()
    }

    private fun buildBarcodeSnapshot(
        title: String,
        state: BarcodeTypeState,
        options: List<BarcodeFormatOptionSnapshot>,
        args: BarcodeTypeRoute.Args,
    ): BarcodeSnapshot = BarcodeSnapshot(
        title = title,
        data = state.request.data,
        format = state.request.format.name,
        formatTitle = state.format.format,
        note = args.text,
        options = options,
        formatSelectable = !args.disallowFormatSelection,
    )

    /**
     * Registers the SwiftUI sink for the passkey credential detail dialog. The
     * callback receives `null` while hidden and a [PasskeyCredentialSnapshot]
     * once a passkey row is clicked. Dismiss via [closePasskeyCredential].
     */
    fun observePasskeyCredential(
        onChange: (PasskeyCredentialSnapshot?) -> Unit,
    ): KeyguardCancellable = passkeyCredentialDialog.register(onChange)

    /**
     * Runs the shared [passkeysCredentialViewStateProducer] headlessly for [args]
     * and projects each emission into a [PasskeyCredentialSnapshot]. Tears down any
     * previously presented instance first. [sessionKoin] resolves the per-session
     * use-cases the producer needs.
     */
    private fun presentPasskeyCredential(
        args: PasskeysCredentialViewRoute.Args,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        passkeyCredentialDialog.present { publish ->
            val title = textResource(Res.string.passkey, leContext)
            ctx.koin.newHeadlessStateFlowScope("passkeys_credential_view", this)
                .passkeysCredentialViewStateProducer(
                    args = args,
                    mode = AppMode.Main,
                    getCiphers = sessionKoin.get(),
                    passkeyTargetCheck = sessionKoin.get(),
                    dateFormatter = sessionKoin.get(),
                )
                .collect { loadable ->
                    val state = loadable.getOrNull() ?: return@collect
                    val snapshot = buildPasskeyCredentialSnapshot(title, state, leContext)
                    publish(snapshot, state.content.getOrNull()?.onUse)
                }
        }
    }

    /** Uses the shown passkey credential (only available in the pick-passkey mode). */
    fun usePasskeyCredential() {
        passkeyCredentialDialog.handlers?.invoke()
    }

    /**
     * Dismisses the passkey credential dialog and tears the headless producer
     * down. The producer's own onClose only pops the (non-existent) navigation
     * stack, so it is intentionally not invoked — same as the Large Type dialog.
     */
    fun closePasskeyCredential() {
        passkeyCredentialDialog.close()
    }

    private suspend fun buildPasskeyCredentialSnapshot(
        title: String,
        state: PasskeysCredentialViewState,
        leContext: LeContext,
    ): PasskeyCredentialSnapshot = state.content.fold(
        ifLeft = { e ->
            PasskeyCredentialSnapshot(
                title = title,
                error = e.message ?: textResource(Res.string.error_failed_unknown, leContext),
            )
        },
        ifRight = { content ->
            val model = content.model
            PasskeyCredentialSnapshot(
                title = title,
                userDisplayName = model.userDisplayName,
                userName = model.userName,
                rpId = model.rpId,
                rpName = model.rpName,
                signatureCounter = model.counter?.toString(),
                discoverable = model.discoverable,
                createdAt = content.createdAt,
                credentialId = model.credentialId,
                canUse = content.onUse != null,
            )
        },
    )

    /**
     * Registers the SwiftUI sink for the attachment preview dialog. The callback
     * receives `null` while hidden and an [AttachmentPreviewSnapshot] once a
     * previewable attachment row is clicked. Dismiss via [closeAttachmentPreview];
     * copy the text content via [invokeAttachmentPreviewCopy].
     */
    fun observeAttachmentPreview(
        onChange: (AttachmentPreviewSnapshot?) -> Unit,
    ): KeyguardCancellable = attachmentPreviewDialog.register(onChange)

    /**
     * Mirrors the effective SwiftUI appearance into [CoreContext.interfaceColorSchemeState]
     * so an open preview re-highlights its code live when the system theme flips.
     */
    fun setInterfaceDarkMode(isDark: Boolean) {
        ctx.scope.launch {
            ctx.interfaceColorSchemeState.value = if (isDark) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
        }
    }

    /**
     * Runs the shared [attachmentPreviewStateProducer] headlessly for [args] and
     * projects each emission into an [AttachmentPreviewSnapshot]. Tears down any
     * previously presented instance first. [sessionKoin] resolves the per-session
     * use-cases the producer needs.
     */
    private fun presentAttachmentPreview(
        args: AttachmentPreviewRoute.Args,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        attachmentPreviewDialog.present { publish ->
            // The producer suspends through download + decryption before its
            // first emission, so push a loading state right away — without it
            // the sheet would only appear once the file is ready.
            publish(
                AttachmentPreviewSnapshot(
                    fileName = args.fileName,
                    kind = AttachmentPreviewKindSnapshot.LOADING,
                ),
                null,
            )
            ctx.koin.newHeadlessStateFlowScope(
                "attachment_preview",
                this,
                colorSchemeState = ctx.interfaceColorSchemeState,
            )
                .attachmentPreviewStateProducer(
                    args = args,
                    canPreviewAttachment = sessionKoin.get(),
                    getAttachmentPreview = sessionKoin.get(),
                    downloadManager = sessionKoin.get(),
                )
                .collect { loadable ->
                    val state = loadable.getOrNull() ?: return@collect
                    val snapshot = buildAttachmentPreviewSnapshot(state, leContext)
                    publish(
                        snapshot,
                        (state.content as? AttachmentPreviewContent.TextLike)?.onCopy,
                    )
                }
        }
    }

    /**
     * Copies the previewed text / markdown content through the shared CopyText
     * (so clipboard auto-clear and copy events keep working). Named `invoke…`
     * rather than `copy…` because Objective-C treats `copy`-prefixed selectors
     * as an ownership family and Kotlin/Native would export it as `doCopy…`.
     */
    fun invokeAttachmentPreviewCopy() {
        attachmentPreviewDialog.handlers?.invoke()
    }

    /** Dismisses the attachment preview dialog and tears the producer down. */
    fun closeAttachmentPreview() {
        attachmentPreviewDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the confirmation dialog. The callback receives
     * `null` while hidden and a [ConfirmationSnapshot] once a confirmation action
     * fires. Mutate items via the `setConfirmationItem*` / `selectConfirmationItem*`
     * methods; finish via [confirmConfirmation] / [denyConfirmation] / [closeConfirmation].
     */
    fun observeConfirmation(
        onChange: (ConfirmationSnapshot?) -> Unit,
    ): KeyguardCancellable = confirmationDialog.register(onChange)

    /**
     * Runs the shared [confirmationStateProducer] headlessly for [args] and projects
     * each emission into a [ConfirmationSnapshot] + the handlers backing the item
     * mutators. Tears down any previously presented instance first.
     *
     * [transmitter] is the registered result receiver recovered from the navigated
     * route; the producer calls it (after popping itself) when the user confirms /
     * denies, which runs the action's real work (e.g. `patchWatchtowerAlertCipher`).
     */
    private fun presentConfirmation(
        args: ConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<ConfirmationResult>,
    ) {
        confirmationDialog.present { publish ->
            // The producer pops itself (PopById) right before transmitting the
            // result; catch it and close the sheet. Hop to the main scope and
            // close async so the transmit line that follows still runs.
            val interceptor: (NavigationIntent) -> Boolean = { navIntent ->
                when (navIntent) {
                    is NavigationIntent.Pop,
                    is NavigationIntent.PopById,
                    -> {
                        ctx.scope.launch { confirmationDialog.close() }
                        true
                    }

                    // An option's "learn more" link.
                    is NavigationIntent.NavigateToBrowser -> {
                        openUrl(navIntent.url)
                        true
                    }

                    else -> false
                }
            }
            var filePickerStarted = false
            ctx.koin.newHeadlessStateFlowScope("confirmation", this, interceptor)
                .confirmationStateProducer(
                    args = args,
                    transmitter = transmitter,
                )
                .collect { state ->
                    // The file-picker side-effect flow is the same instance across
                    // emissions; start collecting it once, on the first emission.
                    if (!filePickerStarted) {
                        filePickerStarted = true
                        val filePickerFlow = state.sideEffects.filePickerIntentFlow
                        launch {
                            filePickerFlow.collect { intent ->
                                ctx.publishOnMain { handleConfirmationFilePickerIntent(intent) }
                            }
                        }
                    }
                    val items = state.items.getOrNull().orEmpty()
                    publish(
                        buildConfirmationSnapshot(args, state, items),
                        buildConfirmationHandlers(state, items),
                    )
                }
        }
    }

    private fun presentTagsConfirmation(
        args: TagsConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<TagsConfirmationResult>,
    ) {
        confirmationDialog.present { publish ->
            val interceptor: (NavigationIntent) -> Boolean = { intent ->
                when (intent) {
                    is NavigationIntent.Pop, is NavigationIntent.PopById -> {
                        ctx.scope.launch { confirmationDialog.close() }
                        true
                    }
                    else -> false
                }
            }
            val leContext = ctx.koin.get<LeContext>()
            ctx.koin.newHeadlessStateFlowScope("tags_confirmation", this, interceptor)
                .tagsConfirmationStateProducer(args, transmitter)
                .collect { state ->
                    publish(
                        ConfirmationSnapshot(
                            title = textResource(Res.string.ciphers_action_change_tags_title, leContext),
                            subtitle = null,
                            message = if (state.items.isEmpty()) textResource(Res.string.tag_none, leContext) else null,
                            items = state.items.map { item ->
                                ConfirmationItemSnapshot(
                                    key = item.key,
                                    kind = ConfirmationItemKind.STRING,
                                    enabled = item.field.onChange != null,
                                    title = "",
                                    stringValue = item.field.text,
                                    stringRevision = item.field.textRevision,
                                    hint = item.field.hint,
                                    removable = item.onRemove != null,
                                )
                            },
                            confirmEnabled = state.onConfirm != null,
                            canAddItem = state.onAdd != null,
                        ),
                        ConfirmationHandlers(
                            stringOnChange = state.items.mapNotNull { item ->
                                item.field.onChange?.let { item.key to it }
                            }.toMap(),
                            onRemove = state.items.mapNotNull { item ->
                                item.onRemove?.let { item.key to it }
                            }.toMap(),
                            onAdd = state.onAdd,
                            onConfirm = state.onConfirm,
                            onDeny = state.onDeny,
                        ),
                    )
                }
        }
    }

    fun addConfirmationItem() {
        confirmationDialog.handlers.onAdd?.invoke()
    }

    fun removeConfirmationItem(key: String) {
        confirmationDialog.handlers.onRemove[key]?.invoke()
    }

    /** Toggles a confirmation BOOLEAN item; the producer re-emits and the snapshot refreshes. */
    fun setConfirmationItemBoolean(key: String, value: Boolean) {
        confirmationDialog.handlers.booleanOnChange[key]?.invoke(value)
    }

    /** Writes [text] into a confirmation STRING item identified by [key]. */
    fun setConfirmationItemString(key: String, text: String) {
        confirmationDialog.handlers.stringOnChange[key]?.invoke(text)
    }

    /** Selects [optionKey] of a confirmation ENUM item identified by [key]. */
    fun selectConfirmationItemEnum(key: String, optionKey: String) {
        confirmationDialog.handlers.enumOnClick[key]?.get(optionKey)?.invoke()
    }

    /** Opens the native file picker for a confirmation FILE item identified by [key]. */
    fun selectConfirmationItemFile(key: String) {
        confirmationDialog.handlers.fileOnSelect[key]?.invoke()
    }

    /** Clears the chosen file of a confirmation FILE item identified by [key]. */
    fun openConfirmationItemDoc(key: String) {
        confirmationDialog.handlers.docOnLearnMore[key]?.invoke()
    }

    fun clearConfirmationItemFile(key: String) {
        confirmationDialog.handlers.fileOnClear[key]?.invoke()
    }

    /** Confirms the dialog (only enabled while every item validates). */
    fun confirmConfirmation() {
        confirmationDialog.handlers.onConfirm?.invoke()
    }

    /** Denies the dialog (transmits a deny result and pops). */
    fun denyConfirmation() {
        confirmationDialog.handlers.onDeny?.invoke()
    }

    /** Dismisses the confirmation dialog and tears the headless producer down. */
    fun closeConfirmation() {
        confirmationDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the master-password re-prompt dialog. The
     * callback receives `null` while hidden and an [ElevatedAccessSnapshot] once a
     * reprompt-protected cipher action fires. Edit the password via
     * [setElevatedAccessPassword]; trigger biometrics / YubiKey via
     * [triggerElevatedAccessBiometric] / [triggerElevatedAccessYubiKey]; finish via
     * [confirmElevatedAccess] / [denyElevatedAccess] / [closeElevatedAccess].
     */
    fun observeElevatedAccess(
        onChange: (ElevatedAccessSnapshot?) -> Unit,
    ): KeyguardCancellable = elevatedAccessDialog.register(onChange)

    /**
     * Runs the shared [elevatedAccessStateProducer] headlessly for the given
     * [transmitter] and projects each emission into an [ElevatedAccessSnapshot] +
     * the handlers backing the password / biometric / YubiKey / confirm closures.
     * Tears down any previously presented instance first.
     *
     * The producer pops itself right before transmitting the result; a local
     * interceptor catches that and closes the sheet (same as [presentConfirmation]).
     * It also emits biometric / YubiKey side-effects, collected once on the first
     * content-carrying emission and routed through the shared [AuthPromptHost] — the
     * biometric prompt auto-fires on first subscription. [sessionKoin] resolves the
     * session-scoped use-cases the producer needs (the master-key verifier etc.).
     */
    private fun presentElevatedAccess(
        transmitter: RouteResultTransmitter<ElevatedAccessResult>,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        elevatedAccessDialog.present { publish ->
            val interceptor: (NavigationIntent) -> Boolean = { navIntent ->
                when (navIntent) {
                    is NavigationIntent.Pop,
                    is NavigationIntent.PopById,
                    -> {
                        ctx.scope.launch { elevatedAccessDialog.close() }
                        true
                    }

                    else -> false
                }
            }
            var promptHostStarted = false
            ctx.koin.newHeadlessStateFlowScope("elevated_access", this, interceptor)
                .elevatedAccessStateProducer(
                    transmitter = transmitter,
                    biometricStatusUseCase = sessionKoin.get(),
                    getBiometricRequireConfirmation = sessionKoin.get(),
                    confirmAccessByPasswordUseCase = sessionKoin.get(),
                    confirmAccessByYubiKeyUseCase = sessionKoin.get(),
                )
                .collect { state ->
                    val content = state.content.getOrNull()
                    // The biometric / YubiKey side-effect flows are the same
                    // instances across emissions; start collecting them once, on the
                    // first content-carrying emission (this also auto-fires the
                    // biometric prompt, matching Compose).
                    if (!promptHostStarted && content != null) {
                        promptHostStarted = true
                        val sideEffects = content.sideEffects
                        launch {
                            sideEffects.showBiometricPromptFlow.collect { prompt ->
                                when (prompt) {
                                    is BiometricAuthPrompt ->
                                        authPromptHost.handleBiometricPrompt(
                                            prompt,
                                            reason = org.jetbrains.compose.resources.getString(
                                                Res.string.elevatedaccess_biometric_auth_confirm_title,
                                            ),
                                        )
                                    is BiometricAuthPromptSimple ->
                                        authPromptHost.handleBiometricPromptSimple(
                                            prompt,
                                            reason = org.jetbrains.compose.resources.getString(
                                                Res.string.elevatedaccess_biometric_auth_confirm_title,
                                            ),
                                        )
                                }
                            }
                        }
                        launch {
                            sideEffects.showYubiKeyPromptFlow.collect { prompt ->
                                when (prompt) {
                                    is YubiKeyAuthPrompt -> authPromptHost.handleYubiKeyPrompt(prompt)
                                }
                            }
                        }
                    }
                    publish(
                        buildElevatedAccessSnapshot(state, leContext),
                        buildElevatedAccessHandlers(state),
                    )
                }
        }
    }

    /** Writes [text] into the re-prompt dialog's password field. */
    fun setElevatedAccessPassword(text: String) {
        elevatedAccessDialog.handlers.passwordOnChange?.invoke(text)
    }

    /** Fires the biometric (Touch ID / Face ID) prompt of the re-prompt dialog. */
    fun triggerElevatedAccessBiometric() {
        elevatedAccessDialog.handlers.onBiometric?.invoke()
    }

    /** Fires the YubiKey challenge-response prompt of the re-prompt dialog. */
    fun triggerElevatedAccessYubiKey() {
        elevatedAccessDialog.handlers.onYubiKey?.invoke()
    }

    /** Confirms the re-prompt with the typed master password (only when it validates). */
    fun confirmElevatedAccess() {
        elevatedAccessDialog.handlers.onConfirm?.invoke()
    }

    /** Denies the re-prompt (transmits a deny result and pops). */
    fun denyElevatedAccess() {
        elevatedAccessDialog.handlers.onDeny?.invoke()
    }

    /** Dismisses the re-prompt dialog and tears the headless producer down. */
    fun closeElevatedAccess() {
        elevatedAccessDialog.close()
    }

    private suspend fun buildElevatedAccessSnapshot(
        state: ElevatedAccessState,
        leContext: LeContext,
    ): ElevatedAccessSnapshot {
        val content = state.content.getOrNull()
        val password = content?.password
        return ElevatedAccessSnapshot(
            title = textResource(Res.string.elevatedaccess_header_title, leContext),
            message = textResource(Res.string.elevatedaccess_header_text, leContext),
            passwordValue = password?.text.orEmpty(),
            passwordRevision = password?.textRevision ?: 0,
            passwordError = password?.error,
            passwordHint = password?.hint,
            hasBiometric = content?.biometric != null,
            biometricEnabled = content?.biometric?.onClick != null,
            hasYubiKey = content?.yubiKey != null,
            yubiKeyEnabled = content?.yubiKey?.onClick != null,
            confirmEnabled = state.onConfirm != null,
            isLoading = content?.isLoading ?: false,
        )
    }

    private fun buildElevatedAccessHandlers(
        state: ElevatedAccessState,
    ): ElevatedAccessHandlers {
        val content = state.content.getOrNull()
        return ElevatedAccessHandlers(
            passwordOnChange = content?.password?.onChange,
            onBiometric = content?.biometric?.onClick,
            onYubiKey = content?.yubiKey?.onClick,
            onConfirm = state.onConfirm,
            onDeny = state.onDeny,
        )
    }

    /**
     * Registers the SwiftUI sink for the service-info dialog. The callback receives
     * `null` while hidden and a [ServiceDirectoryDetailSnapshot] once an inactive
     * TOTP / passkey row is clicked. Dismiss via [closeServiceInfo].
     */
    fun observeServiceInfo(
        onChange: (ServiceDirectoryDetailSnapshot?) -> Unit,
    ): KeyguardCancellable = serviceInfoDialog.register(onChange)

    /** Presents a single static service-info snapshot; tears down any previous one. */
    private fun presentServiceInfo(project: (DirectoryLinkTitles) -> ServiceDirectoryDetailSnapshot) {
        serviceInfoDialog.present { publish ->
            publish(project(directoryLinkTitles(ctx.koin.get())), Unit)
        }
    }

    /** Dismisses the service-info dialog. */
    fun closeServiceInfo() {
        serviceInfoDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the email / username breach dialog. The callback
     * receives `null` while hidden and an [EmailLeakSnapshot] once a "Check data
     * breaches" action fires. Dismiss via [closeEmailLeak].
     */
    fun observeEmailLeak(
        onChange: (EmailLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = emailLeakDialog.register(onChange)

    /**
     * Runs the shared [emailLeakStateProducer] headlessly for [args] and projects each
     * emission into an [EmailLeakSnapshot]. Pushes a loading snapshot first (the
     * producer suspends through the HIBP request before its first emission). Tears
     * down any previously presented instance first. [sessionKoin] resolves the
     * per-session use-cases (the breach checker + date formatter).
     */
    private fun presentEmailLeak(
        args: EmailLeakRoute.Args,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val numberFormatter = ctx.koin.get<NumberFormatter>()
        emailLeakDialog.present { publish ->
            publish(buildEmailLeakLoadingSnapshot(leContext), Unit)
            ctx.koin.newHeadlessStateFlowScope("email_leak", this)
                .emailLeakStateProducer(
                    args = args,
                    checkUsernameLeak = sessionKoin.get(),
                    dateFormatter = sessionKoin.get(),
                )
                .collect { state ->
                    publish(buildEmailLeakSnapshot(state, leContext, numberFormatter), Unit)
                }
        }
    }

    /** Dismisses the email / username breach dialog and tears its producer down. */
    fun closeEmailLeak() {
        emailLeakDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the password breach dialog. The callback receives
     * `null` while hidden and a [PasswordLeakSnapshot] once a "Check data breaches"
     * action fires. Dismiss via [closePasswordLeak].
     */
    fun observePasswordLeak(
        onChange: (PasswordLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = passwordLeakDialog.register(onChange)

    /**
     * Runs the shared [passwordLeakStateProducer] headlessly for [args] and projects
     * each emission into a [PasswordLeakSnapshot]. Pushes a loading snapshot first.
     * Tears down any previously presented instance first. [sessionKoin] resolves the
     * per-session breach checker.
     */
    private fun presentPasswordLeak(
        args: PasswordLeakRoute.Args,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val numberFormatter = ctx.koin.get<NumberFormatter>()
        passwordLeakDialog.present { publish ->
            publish(buildPasswordLeakLoadingSnapshot(leContext), Unit)
            ctx.koin.newHeadlessStateFlowScope("password_leak", this)
                .passwordLeakStateProducer(
                    args = args,
                    checkPasswordLeak = sessionKoin.get(),
                )
                .collect { state ->
                    publish(buildPasswordLeakSnapshot(state, leContext, numberFormatter), Unit)
                }
        }
    }

    /** Dismisses the password breach dialog and tears its producer down. */
    fun closePasswordLeak() {
        passwordLeakDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the website breach dialog. The callback receives
     * `null` while hidden and a [WebsiteLeakSnapshot] once a "Check data breaches"
     * action fires. Dismiss via [closeWebsiteLeak].
     */
    fun observeWebsiteLeak(
        onChange: (WebsiteLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = websiteLeakDialog.register(onChange)

    /**
     * Runs the shared [websiteLeakStateProducer] headlessly for [args] and projects
     * each emission into a [WebsiteLeakSnapshot]. Pushes a loading snapshot first
     * (the producer suspends loading the breach database). Tears down any previously
     * presented instance first. [sessionKoin] resolves the per-session breach
     * repository + date formatter.
     */
    private fun presentWebsiteLeak(
        args: WebsiteLeakRoute.Args,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        val numberFormatter = ctx.koin.get<NumberFormatter>()
        websiteLeakDialog.present { publish ->
            publish(buildWebsiteLeakLoadingSnapshot(leContext), Unit)
            ctx.koin.newHeadlessStateFlowScope("website_leak", this)
                .websiteLeakStateProducer(
                    args = args,
                    getBreaches = sessionKoin.get(),
                    dateFormatter = sessionKoin.get(),
                )
                .collect { state ->
                    publish(buildWebsiteLeakSnapshot(state, leContext, numberFormatter), Unit)
                }
        }
    }

    /** Dismisses the website breach dialog and tears its producer down. */
    fun closeWebsiteLeak() {
        websiteLeakDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the color picker dialog. The callback receives
     * `null` while hidden and a [ColorPickerSnapshot] once the account "Change color"
     * action fires. Select a swatch via [selectColorPickerSwatch]; finish via
     * [confirmColorPicker] / [denyColorPicker] / [closeColorPicker].
     */
    fun observeColorPicker(
        onChange: (ColorPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = colorPickerDialog.register(onChange)

    /**
     * Runs the shared [colorPickerStateProducer] headlessly for [args] and projects each
     * emission into a [ColorPickerSnapshot] + the handlers backing the swatch select /
     * confirm / deny closures. Tears down any previously presented instance first.
     *
     * The producer pops itself right before transmitting the result; a local interceptor
     * catches that and closes the sheet (same as [presentConfirmation]). [transmitter] is
     * the registered result receiver recovered from the navigated route; the producer calls
     * it on confirm, which persists the chosen color via `PutAccountColorById`.
     */
    private fun presentColorPicker(
        args: ColorPickerRoute.Args,
        transmitter: RouteResultTransmitter<ColorPickerResult>,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        colorPickerDialog.present { publish ->
            val title = textResource(Res.string.colorpicker_title, leContext)
            val interceptor: (NavigationIntent) -> Boolean = { navIntent ->
                when (navIntent) {
                    is NavigationIntent.Pop,
                    is NavigationIntent.PopById,
                    -> {
                        ctx.scope.launch { colorPickerDialog.close() }
                        true
                    }

                    else -> false
                }
            }
            ctx.koin.newHeadlessStateFlowScope("color_picker", this, interceptor)
                .colorPickerStateProducer(args, transmitter)
                .collect { loadable ->
                    val state = loadable.getOrNull() ?: return@collect
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val items = state.content.items.map { item ->
                        val id = "swatch:${item.key}"
                        handlers[id] = item.onClick
                        ColorSwatchSnapshot(
                            id = id,
                            index = item.key,
                            argbLight = item.color.light.toArgb().toLong().and(0xFFFFFFFFL),
                            argbDark = item.color.dark.toArgb().toLong().and(0xFFFFFFFFL),
                        )
                    }
                    publish(
                        ColorPickerSnapshot(
                            title = title,
                            items = items,
                            selectedIndex = state.content.index ?: -1,
                            confirmEnabled = state.onConfirm != null,
                        ),
                        ColorPickerHandlers(
                            onSelect = handlers,
                            onConfirm = state.onConfirm,
                            onDeny = state.onDeny,
                        ),
                    )
                }
        }
    }

    /** Highlights a color swatch by routing through the producer's select closure. */
    fun selectColorPickerSwatch(id: String) {
        colorPickerDialog.handlers.onSelect[id]?.invoke()
    }

    /** Confirms the color picker (persists the chosen color) and dismisses. */
    fun confirmColorPicker() {
        colorPickerDialog.handlers.onConfirm?.invoke()
    }

    /** Denies the color picker (no change) and dismisses. */
    fun denyColorPicker() {
        colorPickerDialog.handlers.onDeny?.invoke()
    }

    /** Dismisses the color picker dialog and tears its headless producer down. */
    fun closeColorPicker() {
        colorPickerDialog.close()
    }

    /**
     * Registers the SwiftUI sink for the collection / organization "info" dialog. The
     * callback receives `null` while hidden and an [InfoDialogSnapshot] once a collection /
     * organization row's "Info" action fires. Dismiss via [closeInfoDialog].
     */
    fun observeInfoDialog(
        onChange: (InfoDialogSnapshot?) -> Unit,
    ): KeyguardCancellable = infoDialog.register(onChange)

    /**
     * Runs the shared [collectionScreenStateProducer] headlessly for [args] and projects
     * each emission into an [InfoDialogSnapshot] (the collection name + its read-only /
     * hide-passwords capability flags, mirroring the Compose dialog). Tears down any
     * previously presented instance first. [sessionKoin] resolves the per-session use cases.
     */
    private fun presentCollectionInfo(
        args: CollectionRoute.Args,
        sessionKoin: Scope,
    ) {
        infoDialog.present { publish ->
            val producerScope = this
            val producerFlow = with(sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("collection_info", producerScope)
                    .collectionScreenStateProducer(
                        args = args,
                        getOrganizations = get(),
                        getCollections = get(),
                    )
            }
            producerFlow.collect { state ->
                val content = state.content.getOrNull() ?: return@collect
                publish(buildCollectionInfoSnapshot(content), Unit)
            }
        }
    }

    /**
     * Runs the shared [organizationScreenStateProducer] headlessly for [args] and projects
     * each emission into an [InfoDialogSnapshot] (the organization name + its self-hosted
     * flag). Tears down any previously presented instance first.
     */
    private fun presentOrganizationInfo(
        args: OrganizationRoute.Args,
        sessionKoin: Scope,
    ) {
        infoDialog.present { publish ->
            val producerScope = this
            val producerFlow = with(sessionKoin) {
                ctx.koin.newHeadlessStateFlowScope("organization_info", producerScope)
                    .organizationScreenStateProducer(
                        args = args,
                        getOrganizations = get(),
                    )
            }
            producerFlow.collect { state ->
                val content = state.content.getOrNull() ?: return@collect
                publish(buildOrganizationInfoSnapshot(content), Unit)
            }
        }
    }

    /** Dismisses the collection / organization "info" dialog. */
    fun closeInfoDialog() {
        infoDialog.close()
    }

    // The capability lines mirror the Compose CollectionScreenContent /
    // OrganizationScreenContent (which hardcode these English strings); there are no
    // L10n keys for them in the shared resources.
    private fun buildCollectionInfoSnapshot(
        content: CollectionState.Content,
    ): InfoDialogSnapshot = InfoDialogSnapshot(
        title = content.title,
        subtitle = content.organization?.name,
        flags = buildList {
            if (content.config.readOnly) add("Read only")
            if (content.config.hidePasswords) add("Hide passwords")
        },
    )

    private fun buildOrganizationInfoSnapshot(
        content: OrganizationState.Content,
    ): InfoDialogSnapshot = InfoDialogSnapshot(
        title = content.title,
        subtitle = null,
        flags = buildList {
            if (content.config.selfHost) add("Self-hosted")
        },
    )

    /**
     * Registers the SwiftUI sink for the account picker dialog. The callback receives
     * `null` while hidden and an [AccountPickerSnapshot] once the add form's ownership
     * "Save to" row fires. Select a row via [selectAccountPickerItem]; finish via
     * [confirmAccountPicker] / [denyAccountPicker] / [closeAccountPicker].
     */
    fun observeCipherLinkPicker(
        onChange: (CipherLinkPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = cipherLinkPickerDialog.register(onChange)

    private fun presentCipherLinkPicker(
        args: CipherLinkPickerRoute.Args,
        transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
        sessionKoin: Scope,
    ) {
        cipherLinkPickerDialog.present { publish ->
            val interceptor: (NavigationIntent) -> Boolean = { intent ->
                when (intent) {
                    is NavigationIntent.Pop, is NavigationIntent.PopById -> {
                        ctx.scope.launch { cipherLinkPickerDialog.close() }
                        true
                    }
                    else -> false
                }
            }
            ctx.koin.newHeadlessStateFlowScope("cipher_link_picker", this, interceptor)
                .cipherLinkPickerStateProducer(
                    args = args,
                    transmitter = transmitter,
                    getCiphers = sessionKoin.get(),
                    getAppIcons = sessionKoin.get(),
                    getWebsiteIcons = sessionKoin.get(),
                )
                .collect { state ->
                    val handlers = state.items.associate { it.presentation.source.id to it.onClick }
                    publish(
                        CipherLinkPickerSnapshot(
                            query = state.query.text,
                            queryRevision = state.query.textRevision,
                            items = state.items.map {
                                CipherLinkPickerItemSnapshot(
                                    id = it.presentation.source.id,
                                    title = it.presentation.title.text,
                                    text = it.presentation.text,
                                )
                            },
                        ),
                        CipherLinkPickerHandlers(state.query.onChange, handlers, state.onDeny),
                    )
                }
        }
    }

    fun setCipherLinkPickerQuery(text: String) {
        cipherLinkPickerDialog.handlers.onQuery?.invoke(text)
    }

    fun selectCipherLinkPickerItem(id: String) {
        cipherLinkPickerDialog.handlers.onSelect[id]?.invoke()
    }

    fun closeCipherLinkPicker() {
        cipherLinkPickerDialog.handlers.onDeny?.invoke()
        cipherLinkPickerDialog.close()
    }

    fun observeAccountPicker(
        onChange: (AccountPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = accountPickerDialog.register(onChange)

    /**
     * Runs the shared [organizationConfirmationStateProducer] headlessly for [args]
     * and projects each emission into an [AccountPickerSnapshot] + the handlers backing
     * the row select / confirm / deny closures. Tears down any previously presented
     * instance first.
     *
     * The producer pops itself right before transmitting the result; a local interceptor
     * catches that and closes the sheet (same as [presentConfirmation]). [transmitter] is
     * the registered result receiver recovered from the navigated route; the producer calls
     * it on confirm, which updates the add form's ownership sink. [sessionKoin] resolves the
     * per-session use cases the producer needs.
     */
    private fun presentAccountPicker(
        args: OrganizationConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<OrganizationConfirmationResult>,
        sessionKoin: Scope,
    ) {
        accountPickerDialog.present { publish ->
            val title = args.decor.title
            val interceptor: (NavigationIntent) -> Boolean = { navIntent ->
                when (navIntent) {
                    is NavigationIntent.Pop,
                    is NavigationIntent.PopById,
                    -> {
                        ctx.scope.launch { accountPickerDialog.close() }
                        true
                    }

                    else -> false
                }
            }
            ctx.koin.newHeadlessStateFlowScope("account_picker", this, interceptor)
                .organizationConfirmationStateProducer(
                    args = args,
                    transmitter = transmitter,
                    getAccounts = sessionKoin.get(),
                    getProfiles = sessionKoin.get(),
                    getOrganizations = sessionKoin.get(),
                    getCollections = sessionKoin.get(),
                    getFolders = sessionKoin.get(),
                )
                .collect { state ->
                    val content = state.content.getOrNull() ?: return@collect
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val sections = buildAccountPickerSections(content, handlers)
                    publish(
                        AccountPickerSnapshot(
                            title = title,
                            note = args.decor.note?.text,
                            newFolderName = content.folderNew?.text,
                            newFolderNameRevision = content.folderNew?.textRevision ?: 0,
                            newFolderNameError = content.folderNew?.error,
                            sections = sections,
                            confirmEnabled = state.onConfirm != null,
                        ),
                        AccountPickerHandlers(
                            onSelect = handlers,
                            onNewFolderName = content.folderNew?.onChange,
                            onConfirm = state.onConfirm,
                            onDeny = state.onDeny,
                        ),
                    )
                }
        }
    }

    /**
     * Projects the picker's account / organization / collection / folder sections into
     * flat snapshots, registering each item's select closure under its key. The Send
     * form only ever surfaces the account section (the others are hidden via the route
     * flags, so they arrive null here); the cipher form may surface them all.
     */
    private fun buildAccountPickerSections(
        content: OrganizationConfirmationState.Content,
        handlers: LinkedHashMap<String, () -> Unit>,
    ): List<AccountPickerSectionSnapshot> {
        fun section(
            section: OrganizationConfirmationState.Content.Section?,
        ): AccountPickerSectionSnapshot? {
            section ?: return null
            val items = section.items.map { item ->
                item.onClick?.let { handlers[item.key] = it }
                AccountPickerItemSnapshot(
                    key = item.key,
                    title = item.title,
                    text = item.text,
                    selected = item.selected,
                    enabled = item.onClick != null,
                )
            }
            return AccountPickerSectionSnapshot(title = section.text, items = items)
        }
        return listOfNotNull(
            section(content.accounts),
            section(content.organizations),
            section(content.collections),
            section(content.folders),
        )
    }

    private fun presentFolderPicker(
        args: FolderConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<FolderConfirmationResult>,
        sessionKoin: Scope,
    ) {
        accountPickerDialog.present { publish ->
            val interceptor: (NavigationIntent) -> Boolean = { intent ->
                when (intent) {
                    is NavigationIntent.Pop, is NavigationIntent.PopById -> {
                        ctx.scope.launch { accountPickerDialog.close() }
                        true
                    }
                    else -> false
                }
            }
            ctx.koin.newHeadlessStateFlowScope("folder_picker", this, interceptor)
                .folderConfirmationStateProducer(args, transmitter, sessionKoin.get())
                .collect { state ->
                    val content = state.content.getOrNull() ?: return@collect
                    val handlers = LinkedHashMap<String, () -> Unit>()
                    val items = content.items.map { item ->
                        item.onClick?.let { handlers[item.key] = it }
                        AccountPickerItemSnapshot(
                            key = item.key,
                            title = item.title,
                            text = null,
                            selected = item.selected,
                            enabled = item.onClick != null,
                        )
                    }
                    publish(
                        AccountPickerSnapshot(
                            title = textResource(
                                Res.string.ciphers_action_change_folder_title,
                                ctx.koin.get(),
                            ),
                            sections = listOf(AccountPickerSectionSnapshot(null, items)),
                            confirmEnabled = state.onConfirm != null,
                            newFolderName = content.new?.text,
                            newFolderNameRevision = content.new?.textRevision ?: 0,
                            newFolderNameError = content.new?.error,
                        ),
                        AccountPickerHandlers(
                            onSelect = handlers,
                            onNewFolderName = content.new?.onChange,
                            onConfirm = state.onConfirm,
                            onDeny = state.onDeny,
                        ),
                    )
                }
        }
    }

    fun setAccountPickerNewFolderName(text: String) {
        accountPickerDialog.handlers.onNewFolderName?.invoke(text)
    }

    /** Selects an account picker row by its key (re-runs the producer with the new selection). */
    fun selectAccountPickerItem(key: String) {
        accountPickerDialog.handlers.onSelect[key]?.invoke()
    }

    /** Confirms the account picker (transmits the chosen ownership) and dismisses. */
    fun confirmAccountPicker() {
        accountPickerDialog.handlers.onConfirm?.invoke()
    }

    /** Denies the account picker (no change) and dismisses. */
    fun denyAccountPicker() {
        accountPickerDialog.handlers.onDeny?.invoke()
    }

    /** Dismisses the account picker dialog and tears its headless producer down. */
    fun closeAccountPicker() {
        accountPickerDialog.close()
    }

    /** Registers the SwiftUI sink that presents a native file panel for confirmation FILE items. */
    fun setConfirmationFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) {
        onConfirmationFilePickerRequest = handler
    }

    /** Feeds a chosen file back into the confirmation producer continuation for [requestId]. */
    fun resolveConfirmationFilePicker(requestId: String, uri: String, name: String?, size: Long) {
        val handler = confirmationFilePickerHandlers.remove(requestId) ?: return
        handler(filePickerResultOf(uri, name, size))
    }

    /** Cancels an in-flight confirmation file-picker request for [requestId]. */
    fun cancelConfirmationFilePicker(requestId: String) {
        val handler = confirmationFilePickerHandlers.remove(requestId) ?: return
        handler(null)
    }

    /** Translates a producer [FilePickerIntent] into an [AddFilePickerRequest] for Swift. */
    private fun handleConfirmationFilePickerIntent(intent: FilePickerIntent<*>) {
        val requestId = "cfp:${confirmationFilePickerRequestCounter++}"
        @Suppress("UNCHECKED_CAST")
        val onResult = intent.onResult as (FilePickerResult?) -> Unit
        confirmationFilePickerHandlers[requestId] = onResult
        val request = when (intent) {
            is FilePickerIntent.OpenDocument -> AddFilePickerRequest(
                requestId = requestId,
                kind = AddFilePickerKind.OPEN_DOCUMENT,
                mimeTypes = intent.mimeTypes.toList(),
                suggestedName = null,
            )

            is FilePickerIntent.OpenDirectory -> AddFilePickerRequest(
                requestId = requestId,
                kind = AddFilePickerKind.OPEN_DIRECTORY,
                mimeTypes = emptyList(),
                suggestedName = null,
            )

            is FilePickerIntent.NewDocument -> AddFilePickerRequest(
                requestId = requestId,
                kind = AddFilePickerKind.NEW_DOCUMENT,
                mimeTypes = listOf(intent.mimeType),
                suggestedName = intent.fileName,
            )
        }
        onConfirmationFilePickerRequest?.invoke(request)
    }

    private fun buildConfirmationSnapshot(
        args: ConfirmationRoute.Args,
        state: ConfirmationState,
        items: List<ConfirmationState.Item>,
    ): ConfirmationSnapshot = ConfirmationSnapshot(
        title = args.title,
        subtitle = args.subtitle,
        message = args.message,
        items = items.map { it.toConfirmationItemSnapshot() },
        confirmEnabled = state.onConfirm != null,
        docUrl = args.docUrl,
    )

    private fun ConfirmationState.Item.toConfirmationItemSnapshot(): ConfirmationItemSnapshot = when (this) {
        is ConfirmationState.Item.BooleanItem -> ConfirmationItemSnapshot(
            key = key,
            kind = ConfirmationItemKind.BOOLEAN,
            enabled = enabled,
            title = title,
            text = text,
            booleanValue = value,
        )

        is ConfirmationState.Item.StringItem -> ConfirmationItemSnapshot(
            key = key,
            kind = ConfirmationItemKind.STRING,
            enabled = enabled,
            title = title,
            stringValue = state.text,
            stringRevision = state.textRevision,
            descriptionText = description,
            hint = state.hint,
            error = state.error,
            sensitive = sensitive,
            monospace = monospace,
            password = password,
        )

        is ConfirmationState.Item.EnumItem -> ConfirmationItemSnapshot(
            key = key,
            kind = ConfirmationItemKind.CHOICE,
            enabled = enabled,
            // EnumItem has no title of its own; it is just a list of options.
            title = "",
            enumValue = value,
            options = items.map { option ->
                ConfirmationEnumOptionSnapshot(
                    key = option.key,
                    title = option.title,
                    text = option.text,
                    selected = option.selected,
                )
            },
            docText = doc?.text,
            docHasLink = doc?.onLearnMore != null,
        )

        is ConfirmationState.Item.FileItem -> ConfirmationItemSnapshot(
            key = key,
            kind = ConfirmationItemKind.FILE,
            enabled = enabled,
            title = title,
            error = error,
            fileName = value?.name,
            hasFile = value != null,
        )
    }

    private fun buildConfirmationHandlers(
        state: ConfirmationState,
        items: List<ConfirmationState.Item>,
    ): ConfirmationHandlers {
        val booleanOnChange = LinkedHashMap<String, (Boolean) -> Unit>()
        val stringOnChange = LinkedHashMap<String, (String) -> Unit>()
        val enumOnClick = LinkedHashMap<String, Map<String, () -> Unit>>()
        val fileOnSelect = LinkedHashMap<String, () -> Unit>()
        val fileOnClear = LinkedHashMap<String, () -> Unit>()
        val docOnLearnMore = LinkedHashMap<String, () -> Unit>()
        items.forEach { item ->
            when (item) {
                is ConfirmationState.Item.BooleanItem ->
                    booleanOnChange[item.key] = item.onChange

                is ConfirmationState.Item.StringItem ->
                    item.state.onChange?.let { stringOnChange[item.key] = it }

                is ConfirmationState.Item.EnumItem -> {
                    val optionHandlers = LinkedHashMap<String, () -> Unit>()
                    item.items.forEach { option ->
                        option.onClick?.let { optionHandlers[option.key] = it }
                    }
                    enumOnClick[item.key] = optionHandlers
                    item.doc?.onLearnMore?.let { docOnLearnMore[item.key] = it }
                }

                is ConfirmationState.Item.FileItem -> {
                    fileOnSelect[item.key] = item.onSelect
                    item.onClear?.let { fileOnClear[item.key] = it }
                }
            }
        }
        return ConfirmationHandlers(
            booleanOnChange = booleanOnChange,
            stringOnChange = stringOnChange,
            enumOnClick = enumOnClick,
            fileOnSelect = fileOnSelect,
            fileOnClear = fileOnClear,
            docOnLearnMore = docOnLearnMore,
            onConfirm = state.onConfirm,
            onDeny = state.onDeny,
        )
    }

    // ---------------------------------------------------------------------------
    // HIBP breach dialog snapshot builders. The user-facing text is resolved here
    // (Kotlin side) because plural resolution + number formatting live in Kotlin;
    // SwiftUI only renders the pre-resolved strings + the favicon / chips / dates.
    // ---------------------------------------------------------------------------

    private suspend fun buildEmailLeakLoadingSnapshot(
        leContext: LeContext,
    ): EmailLeakSnapshot = EmailLeakSnapshot(
        title = textResource(Res.string.emailleak_title, leContext),
        note = textResource(Res.string.emailleak_note, leContext),
        poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
        isLoading = true,
        breachFoundTitle = textResource(Res.string.emailleak_breach_found_title, leContext),
        breachNotFoundTitle = textResource(Res.string.emailleak_breach_not_found_title, leContext),
        breachSectionTitle = textResource(Res.string.emailleak_breach_section, leContext),
    )

    private suspend fun buildEmailLeakSnapshot(
        state: EmailLeakState,
        leContext: LeContext,
        numberFormatter: NumberFormatter,
    ): EmailLeakSnapshot {
        val breaches = state.content.getOrNull()?.breaches
        // Mirror the Compose Content() error mapping: a 404 means the account has no
        // HIBP API key configured; anything else is a generic load failure.
        val errorText = if (breaches == null) {
            when (val e = state.content.swap().getOrNull()) {
                is HttpException ->
                    if (e.statusCode.value == 404) {
                        textResource(Res.string.emailleak_failed_no_api_found_text, leContext)
                    } else {
                        textResource(Res.string.emailleak_failed_to_load_status_text, leContext)
                    }

                else -> textResource(Res.string.emailleak_failed_to_load_status_text, leContext)
            }
        } else {
            null
        }
        return EmailLeakSnapshot(
            title = textResource(Res.string.emailleak_title, leContext),
            note = textResource(Res.string.emailleak_note, leContext),
            poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
            isLoading = false,
            breaches = breaches?.map { it.toLeakBreachSnapshot(leContext, numberFormatter) }.orEmpty(),
            errorText = errorText,
            breachFoundTitle = textResource(Res.string.emailleak_breach_found_title, leContext),
            breachNotFoundTitle = textResource(Res.string.emailleak_breach_not_found_title, leContext),
            breachSectionTitle = textResource(Res.string.emailleak_breach_section, leContext),
        )
    }

    private suspend fun EmailLeakState.Breach.toLeakBreachSnapshot(
        leContext: LeContext,
        numberFormatter: NumberFormatter,
    ): LeakBreachSnapshot = LeakBreachSnapshot(
        title = title,
        domain = domain,
        descriptionText = description,
        icon = icon,
        count = count,
        countText = count?.let {
            textResource(
                Res.plurals.emailleak_breach_accounts_count_plural,
                leContext,
                it,
                numberFormatter.formatNumber(it),
            )
        },
        occurredAt = occurredAt?.let { textResource(Res.string.emailleak_breach_occurred_at, leContext, it) },
        reportedAt = reportedAt?.let { textResource(Res.string.emailleak_breach_reported_at, leContext, it) },
        dataClasses = dataClasses,
    )

    private suspend fun buildPasswordLeakLoadingSnapshot(
        leContext: LeContext,
    ): PasswordLeakSnapshot = PasswordLeakSnapshot(
        title = textResource(Res.string.passwordleak_title, leContext),
        note = textResource(Res.string.passwordleak_note, leContext),
        poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
        isLoading = true,
        occurrencesFoundTitle = textResource(Res.string.passwordleak_occurrences_found_title, leContext),
        occurrencesFoundText = textResource(Res.string.passwordleak_occurrences_found_text, leContext),
        occurrencesNotFoundTitle = textResource(Res.string.passwordleak_occurrences_not_found_title, leContext),
    )

    private suspend fun buildPasswordLeakSnapshot(
        state: PasswordLeakState,
        leContext: LeContext,
        numberFormatter: NumberFormatter,
    ): PasswordLeakSnapshot {
        val occurrences = state.content.getOrNull()?.occurrences
        return PasswordLeakSnapshot(
            title = textResource(Res.string.passwordleak_title, leContext),
            note = textResource(Res.string.passwordleak_note, leContext),
            poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
            isLoading = false,
            occurrences = occurrences,
            occurrencesText = occurrences
                ?.takeIf { it > 0 }
                ?.let {
                    textResource(
                        Res.plurals.passwordleak_occurrences_count_plural,
                        leContext,
                        it,
                        numberFormatter.formatNumber(it),
                    )
                },
            errorText = if (occurrences == null) {
                textResource(Res.string.passwordleak_failed_to_load_status_text, leContext)
            } else {
                null
            },
            occurrencesFoundTitle = textResource(Res.string.passwordleak_occurrences_found_title, leContext),
            occurrencesFoundText = textResource(Res.string.passwordleak_occurrences_found_text, leContext),
            occurrencesNotFoundTitle = textResource(Res.string.passwordleak_occurrences_not_found_title, leContext),
        )
    }

    private suspend fun buildWebsiteLeakLoadingSnapshot(
        leContext: LeContext,
    ): WebsiteLeakSnapshot = WebsiteLeakSnapshot(
        // The Compose website dialog reuses the email-leak title / note / breach
        // header strings, so mirror that here.
        title = textResource(Res.string.emailleak_title, leContext),
        note = textResource(Res.string.emailleak_note, leContext),
        poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
        isLoading = true,
        breachFoundTitle = textResource(Res.string.emailleak_breach_found_title, leContext),
        breachNotFoundTitle = textResource(Res.string.emailleak_breach_not_found_title, leContext),
        breachSectionTitle = textResource(Res.string.emailleak_breach_section, leContext),
    )

    private suspend fun buildWebsiteLeakSnapshot(
        state: WebsiteLeakState,
        leContext: LeContext,
        numberFormatter: NumberFormatter,
    ): WebsiteLeakSnapshot = WebsiteLeakSnapshot(
        title = textResource(Res.string.emailleak_title, leContext),
        note = textResource(Res.string.emailleak_note, leContext),
        poweredBy = textResource(Res.string.watchtower_hibp_attribution_text, leContext),
        isLoading = false,
        breaches = state.content.getOrNull()?.breaches.orEmpty()
            .map { it.toLeakBreachSnapshot(leContext, numberFormatter) },
        breachFoundTitle = textResource(Res.string.emailleak_breach_found_title, leContext),
        breachNotFoundTitle = textResource(Res.string.emailleak_breach_not_found_title, leContext),
        breachSectionTitle = textResource(Res.string.emailleak_breach_section, leContext),
    )

    private suspend fun WebsiteLeakState.Breach.toLeakBreachSnapshot(
        leContext: LeContext,
        numberFormatter: NumberFormatter,
    ): LeakBreachSnapshot = LeakBreachSnapshot(
        title = title,
        domain = domain,
        descriptionText = description,
        icon = icon,
        count = count,
        countText = count?.let {
            textResource(
                Res.plurals.emailleak_breach_accounts_count_plural,
                leContext,
                it,
                numberFormatter.formatNumber(it),
            )
        },
        occurredAt = occurredAt?.let { textResource(Res.string.emailleak_breach_occurred_at, leContext, it) },
        reportedAt = reportedAt?.let { textResource(Res.string.emailleak_breach_reported_at, leContext, it) },
        dataClasses = dataClasses,
    )

    private suspend fun buildAttachmentPreviewSnapshot(
        state: AttachmentPreviewState,
        leContext: LeContext,
    ): AttachmentPreviewSnapshot = when (val content = state.content) {
        is AttachmentPreviewContent.Image -> AttachmentPreviewSnapshot(
            fileName = state.fileName,
            kind = AttachmentPreviewKindSnapshot.IMAGE,
            imageData = content.bytes.toNSData(),
            imageDecodeErrorMessage = textResource(
                Res.string.attachment_preview_error_image_decode,
                leContext,
            ),
        )

        is AttachmentPreviewContent.Text -> AttachmentPreviewSnapshot(
            fileName = state.fileName,
            kind = AttachmentPreviewKindSnapshot.TEXT,
            text = content.code.text,
            spans = content.code.annotatedString.toAttachmentPreviewSpans(),
            canCopy = true,
        )

        is AttachmentPreviewContent.Markdown -> AttachmentPreviewSnapshot(
            fileName = state.fileName,
            kind = AttachmentPreviewKindSnapshot.MARKDOWN,
            text = content.code.text,
            spans = content.code.annotatedString.toAttachmentPreviewSpans(),
            canCopy = true,
        )

        is AttachmentPreviewContent.Error -> AttachmentPreviewSnapshot(
            fileName = state.fileName,
            kind = AttachmentPreviewKindSnapshot.ERROR,
            errorMessage = content.type.toMessage(leContext),
        )
    }

    // Mirrors the shared Compose AttachmentPreviewError.message() mapping.
    private suspend fun AttachmentPreviewError.toMessage(
        leContext: LeContext,
    ): String = when (this) {
        AttachmentPreviewError.UnsupportedFileType ->
            textResource(Res.string.attachment_preview_error_unsupported_file, leContext)

        AttachmentPreviewError.UnsupportedPlatform ->
            textResource(Res.string.attachment_preview_error_unsupported_platform, leContext)

        AttachmentPreviewError.TooLarge ->
            textResource(Res.string.attachment_preview_error_too_large, leContext)

        AttachmentPreviewError.Network ->
            textResource(Res.string.attachment_preview_error_network, leContext)

        AttachmentPreviewError.Decryption ->
            textResource(Res.string.attachment_preview_error_decryption, leContext)

        AttachmentPreviewError.TextDecode ->
            textResource(Res.string.attachment_preview_error_text_decode, leContext)

        AttachmentPreviewError.Unknown ->
            textResource(Res.string.error_failed_unknown, leContext)
    }
}
