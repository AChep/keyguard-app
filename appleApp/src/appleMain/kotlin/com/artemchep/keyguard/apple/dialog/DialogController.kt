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
import com.artemchep.keyguard.URL_HAVE_I_BEEN_PWNED
import com.artemchep.keyguard.common.model.BiometricAuthPrompt
import com.artemchep.keyguard.common.model.AttachmentPreviewLimits
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
import com.artemchep.keyguard.feature.filepicker.humanReadableByteCountBin
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
import com.artemchep.keyguard.apple.add.AddFilePickerRequest
import com.artemchep.keyguard.apple.auth.AuthPromptHost
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.directory.DirectoryLinkTitles
import com.artemchep.keyguard.apple.directory.ServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.directory.directoryLinkTitles
import com.artemchep.keyguard.apple.directory.toServiceDirectoryDetailSnapshot
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.resultRouteOrNull
import com.artemchep.keyguard.apple.core.routeOrNull
import com.artemchep.keyguard.apple.core.toArgbLong
import com.artemchep.keyguard.util.io.toNSData
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.onFilePickerResult
import com.artemchep.keyguard.apple.core.toFilePickerRequest
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
 * SwiftUI-presented dialogs (iOS + macOS). Each dialog is a [DialogHost] channel
 * fed by a shared headless producer. Screens pass [navigationInterceptor] to their
 * producer; it catches the dialog routes and presents them here.
 */
internal class DialogController(
    private val ctx: CoreContext,
    private val authPromptHost: AuthPromptHost,
) {
    private var closed = false
    private val disposeDialogs = mutableListOf<() -> Unit>()

    fun close() {
        if (closed) return
        closed = true
        disposeDialogs.forEach { it() }
        confirmationFilePickerHandlers.clear()
        onConfirmationFilePickerRequest = null
    }

    /**
     * One dialog channel: a Swift sink (`null` while hidden), at most one headless producer job, and the
     * per-presentation [handlers] behind the dialog's Swift-facing methods. All fields are main-confined:
     * [register], [present] and [close] run on main (the navigation interceptor hops before presenting).
     * The producer runs on [CoreContext.backgroundScope]; its `publish` installs the handlers and pushes the
     * snapshot on main.
     */
    private inner class DialogHost<S : Any, H>(
        private val noHandlers: H,
    ) {
        private var sink: ((S?) -> Unit)? = null
        private var job: Job? = null
        private var registration: Any? = null
        private var presentation: Any? = null

        init {
            disposeDialogs += {
                close()
                sink = null
                registration = null
            }
        }

        var handlers: H = noHandlers
            private set

        /** Registers the SwiftUI sink; emits `null` (hidden) right away. */
        fun register(onChange: (S?) -> Unit): KeyguardCancellable {
            if (closed) return KeyguardCancellable {}
            val owner = Any()
            registration = owner
            sink = onChange
            val initial = ctx.scope.launch {
                if (!closed && registration === owner) onChange(null)
            }
            return KeyguardCancellable {
                initial.cancel()
                if (registration === owner) {
                    close()
                    sink = null
                    registration = null
                }
            }
        }

        /**
         * Replaces the running producer with [block], which delivers each snapshot together with the handlers
         * behind its ids through `publish`. No-op while no sink is registered.
         */
        fun present(
            block: suspend CoroutineScope.(publish: suspend (S, H) -> Unit) -> Unit,
        ) {
            if (closed || sink == null) return
            job?.cancel()
            handlers = noHandlers
            val owner = Any()
            presentation = owner
            job = ctx.backgroundScope.launch {
                block { snapshot, newHandlers ->
                    ctx.publishOnMain {
                        if (closed || presentation !== owner) return@publishOnMain
                        handlers = newHandlers
                        sink?.invoke(snapshot)
                    }
                }
            }
        }

        /**
         * An interceptor that closes the dialog once its producer pops itself.
         * Producers pop right before transmitting their result, so the close hops
         * to the main scope and runs async: the transmit that follows still runs.
         */
        fun closeOnPop(): (NavigationIntent) -> Boolean = { intent ->
            val pop = intent is NavigationIntent.Pop || intent is NavigationIntent.PopById
            if (pop) ctx.scope.launch { close() }
            pop
        }

        fun close() {
            presentation = null
            job?.cancel()
            job = null
            handlers = noHandlers
            sink?.invoke(null)
        }
    }

    private val largeTypeDialog =
        DialogHost<LargeTypeSnapshot, Map<Int, () -> Unit>>(emptyMap())

    private val barcodeDialog =
        DialogHost<BarcodeSnapshot, Map<String, () -> Unit>>(emptyMap())

    private val passkeyCredentialDialog =
        DialogHost<PasskeyCredentialSnapshot, (() -> Unit)?>(null)

    private val attachmentPreviewDialog =
        DialogHost<AttachmentPreviewSnapshot, (() -> Unit)?>(null)

    /** The maps are keyed by the snapshot item / option keys SwiftUI passes back. */
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
    )

    private val confirmationDialog =
        DialogHost<ConfirmationSnapshot, ConfirmationHandlers>(ConfirmationHandlers())

    /**
     * Opens a link a dialog producer emits ([NavigationIntent.NavigateToBrowser]).
     * Late-bound by `KeyguardCore` to the navigation stack's open-url handler.
     */
    var openUrl: (String) -> Unit = {}

    private class ElevatedAccessHandlers(
        val passwordOnChange: ((String) -> Unit)? = null,
        val onBiometric: (() -> Unit)? = null,
        val onYubiKey: (() -> Unit)? = null,
        val onConfirm: (() -> Unit)? = null,
    )

    private val elevatedAccessDialog =
        DialogHost<ElevatedAccessSnapshot, ElevatedAccessHandlers>(ElevatedAccessHandlers())

    /** The route args carry the full service model, so this dialog is one static snapshot with no producer. */
    private val serviceInfoDialog =
        DialogHost<ServiceDirectoryDetailSnapshot, Unit>(Unit)

    private val emailLeakDialog =
        DialogHost<EmailLeakSnapshot, Unit>(Unit)

    private val passwordLeakDialog =
        DialogHost<PasswordLeakSnapshot, Unit>(Unit)

    private val websiteLeakDialog =
        DialogHost<WebsiteLeakSnapshot, Unit>(Unit)

    private val colorPickerDialog =
        DialogHost<ColorPickerSnapshot, ColorPickerHandlers>(ColorPickerHandlers())

    private class ColorPickerHandlers(
        // swatchId -> select closure
        val onSelect: Map<String, () -> Unit> = emptyMap(),
        val onConfirm: (() -> Unit)? = null,
    )

    private val infoDialog =
        DialogHost<InfoDialogSnapshot, Unit>(Unit)

    private class AccountPickerHandlers(
        val onNewFolderName: ((String) -> Unit)? = null,
        // itemKey -> select closure
        val onSelect: Map<String, () -> Unit> = emptyMap(),
        val onConfirm: (() -> Unit)? = null,
    )

    /** Shared by the account picker and the folder picker ([presentFolderPicker]). */
    private val accountPickerDialog =
        DialogHost<AccountPickerSnapshot, AccountPickerHandlers>(AccountPickerHandlers())

    private data class CipherLinkPickerHandlers(
        val onQuery: ((String) -> Unit)? = null,
        val onSelect: Map<String, () -> Unit> = emptyMap(),
    )

    private val cipherLinkPickerDialog =
        DialogHost<CipherLinkPickerSnapshot, CipherLinkPickerHandlers>(CipherLinkPickerHandlers())

    // Confirmation FILE items: each producer FilePickerIntent becomes an AddFilePickerRequest (the create-form
    // type, so Swift reuses its file panel) and waits here, keyed by request id, until Swift resolves or cancels it.
    private var onConfirmationFilePickerRequest: ((AddFilePickerRequest) -> Unit)? = null
    private val confirmationFilePickerHandlers = LinkedHashMap<String, (FilePickerResult?) -> Unit>()
    private var confirmationFilePickerRequestCounter = 0

    /**
     * Catches every dialog route handled below, presents the matching dialog and
     * returns true. Other intents return false. Most dialogs need [sessionKoin] for
     * their per-session use cases and are not caught without it.
     */
    fun navigationInterceptor(
        sessionKoin: Scope? = null,
        formDialogsOnly: Boolean = false,
    ): (NavigationIntent) -> Boolean = interceptor@ { intent ->
        val passwordMemory = intent.routeOrNull<PasswordMemoryRoute>()
        val largeTypeArgs = intent.toLargeTypeArgsOrNull()
        val barcodeArgs = intent.routeOrNull<BarcodeTypeRoute>()?.args
        val passkeyCredentialArgs = intent.routeOrNull<PasskeysCredentialViewRoute>()?.args
        val attachmentPreviewArgs = intent.routeOrNull<AttachmentPreviewRoute>()?.args
        val serviceInfo = intent.toServiceInfoOrNull()
        val emailLeakArgs = intent.routeOrNull<EmailLeakRoute>()?.args
        val passwordLeakArgs = intent.routeOrNull<PasswordLeakRoute>()?.args
        val websiteLeakArgs = intent.routeOrNull<WebsiteLeakRoute>()?.args
        val collectionInfoArgs = intent.routeOrNull<CollectionRoute>()?.args
        val organizationInfoArgs = intent.routeOrNull<OrganizationRoute>()?.args
        // These dialogs return a result, so their routes come wrapped by
        // registerRouteResultReceiver; unwrap the route and its result transmitter.
        val confirmation = intent.resultRouteOrNull<ConfirmationRoute, ConfirmationResult>()
        val tagsConfirmation = intent.resultRouteOrNull<TagsConfirmationRoute, TagsConfirmationResult>()
        val elevatedAccess = intent.resultRouteOrNull<ElevatedAccessRoute, ElevatedAccessResult>()?.second
        val colorPicker = intent.resultRouteOrNull<ColorPickerRoute, ColorPickerResult>()
        val cipherLinkPicker = intent.resultRouteOrNull<CipherLinkPickerRoute, CipherLinkPickerResult>()
        val accountPicker = intent.resultRouteOrNull<OrganizationConfirmationRoute, OrganizationConfirmationResult>()
        val folderPicker = intent.resultRouteOrNull<FolderConfirmationRoute, FolderConfirmationResult>()
        val hasConfirmation = confirmation != null || tagsConfirmation != null
        val hasPicker = cipherLinkPicker != null || accountPicker != null || folderPicker != null
        if (formDialogsOnly && !hasConfirmation && !hasPicker) return@interceptor false
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
                    presentCipherLinkPicker(cipherLinkPicker.first.args, cipherLinkPicker.second, sessionKoin)
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
                ctx.scope.launch { presentTagsConfirmation(tagsConfirmation.first.args, tagsConfirmation.second) }
                true
            }

            confirmation != null -> {
                ctx.scope.launch { presentConfirmation(confirmation.first.args, confirmation.second) }
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
                ctx.scope.launch { presentColorPicker(colorPicker.first.args, colorPicker.second) }
                true
            }

            folderPicker != null && sessionKoin != null -> {
                ctx.scope.launch { presentFolderPicker(folderPicker.first.args, folderPicker.second, sessionKoin) }
                true
            }

            accountPicker != null && sessionKoin != null -> {
                ctx.scope.launch { presentAccountPicker(accountPicker.first.args, accountPicker.second, sessionKoin) }
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

    // Returns a projection, not a snapshot: it needs the localized link titles, resolved when the dialog presents.
    private fun NavigationIntent.toServiceInfoOrNull(): ((DirectoryLinkTitles) -> ServiceDirectoryDetailSnapshot)? {
        val route = (this as? NavigationIntent.NavigateToRoute)?.route ?: return null
        return when (route) {
            is TwoFaServiceViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            is PasskeysServiceViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            is JustGetMyDataViewDialogRoute -> { titles -> route.args.model.toServiceDirectoryDetailSnapshot(titles) }
            is JustDeleteMeServiceViewDialogRoute -> { titles ->
                route.args.justDeleteMe.toServiceDirectoryDetailSnapshot(titles)
            }
            else -> null
        }
    }

    private val passwordMemoryDialog = DialogHost<PasswordMemorySnapshot, PasswordMemoryState?>(null)

    fun observePasswordMemory(onChange: (PasswordMemorySnapshot?) -> Unit): KeyguardCancellable =
        passwordMemoryDialog.register(onChange)

    fun setPasswordMemoryText(text: String) { passwordMemoryDialog.handlers?.password?.onChange?.invoke(text) }
    fun verifyPasswordMemory() { passwordMemoryDialog.handlers?.onVerify?.invoke() }
    fun closePasswordMemory() { passwordMemoryDialog.close() }

    private fun presentPasswordMemory(args: PasswordMemoryRoute.Args) {
        passwordMemoryDialog.present { publish ->
            ctx.koin.newHeadlessStateFlowScope("password_memory", this, passwordMemoryDialog.closeOnPop())
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

    fun selectLargeTypeSymbol(index: Int) {
        largeTypeDialog.handlers[index]?.invoke()
    }

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

    // Mirrors the colorize decision in the Compose SymbolItem.
    private fun LargeTypeState.Item.toSymbolColor(): LargeTypeSymbolColor = when {
        !colorize || text.length > 1 -> LargeTypeSymbolColor.PLAIN
        text[0].isDigit() -> LargeTypeSymbolColor.DIGIT
        text[0].isLetter() -> LargeTypeSymbolColor.PLAIN
        else -> LargeTypeSymbolColor.SYMBOL
    }

    fun observeBarcode(
        onChange: (BarcodeSnapshot?) -> Unit,
    ): KeyguardCancellable = barcodeDialog.register(onChange)

    /**
     * The barcode-usage-history use cases are vault-session scoped, so this root-graph lookup returns null and
     * the producer falls back to on-disk format persistence.
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

    fun selectBarcodeFormat(id: String) {
        barcodeDialog.handlers[id]?.invoke()
    }

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

    fun observePasskeyCredential(
        onChange: (PasskeyCredentialSnapshot?) -> Unit,
    ): KeyguardCancellable = passkeyCredentialDialog.register(onChange)

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

    fun usePasskeyCredential() {
        passkeyCredentialDialog.handlers?.invoke()
    }

    /** The producer's onClose only pops the (non-existent) navigation stack, so it is intentionally not invoked. */
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

    fun observeAttachmentPreview(
        onChange: (AttachmentPreviewSnapshot?) -> Unit,
    ): KeyguardCancellable = attachmentPreviewDialog.register(onChange)

    fun setInterfaceDarkMode(isDark: Boolean) {
        ctx.scope.launch {
            ctx.interfaceColorSchemeState.value = if (isDark) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
        }
    }

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

    fun invokeAttachmentPreviewCopy() {
        attachmentPreviewDialog.handlers?.invoke()
    }

    fun closeAttachmentPreview() {
        attachmentPreviewDialog.close()
    }

    fun observeConfirmation(
        onChange: (ConfirmationSnapshot?) -> Unit,
    ): KeyguardCancellable = confirmationDialog.register(onChange)

    /** The producer calls [transmitter] after popping itself on confirm; that runs the action's real work. */
    private fun presentConfirmation(
        args: ConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<ConfirmationResult>,
    ) {
        confirmationDialog.present { publish ->
            val closeOnPop = confirmationDialog.closeOnPop()
            val interceptor: (NavigationIntent) -> Boolean = { navIntent ->
                if (navIntent is NavigationIntent.NavigateToBrowser) {
                    // An option's "learn more" link.
                    openUrl(navIntent.url)
                    true
                } else {
                    closeOnPop(navIntent)
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
            val interceptor = confirmationDialog.closeOnPop()
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

    fun setConfirmationItemBoolean(key: String, value: Boolean) {
        confirmationDialog.handlers.booleanOnChange[key]?.invoke(value)
    }

    fun setConfirmationItemString(key: String, text: String) {
        confirmationDialog.handlers.stringOnChange[key]?.invoke(text)
    }

    fun selectConfirmationItemEnum(key: String, optionKey: String) {
        confirmationDialog.handlers.enumOnClick[key]?.get(optionKey)?.invoke()
    }

    /** Opens the native file picker for a confirmation FILE item identified by [key]. */
    fun selectConfirmationItemFile(key: String) {
        confirmationDialog.handlers.fileOnSelect[key]?.invoke()
    }

    fun openConfirmationItemDoc(key: String) {
        confirmationDialog.handlers.docOnLearnMore[key]?.invoke()
    }

    fun clearConfirmationItemFile(key: String) {
        confirmationDialog.handlers.fileOnClear[key]?.invoke()
    }

    fun confirmConfirmation() {
        confirmationDialog.handlers.onConfirm?.invoke()
    }

    fun closeConfirmation() {
        confirmationDialog.close()
    }

    fun observeElevatedAccess(
        onChange: (ElevatedAccessSnapshot?) -> Unit,
    ): KeyguardCancellable = elevatedAccessDialog.register(onChange)

    /** Biometric / YubiKey side-effects go through the shared [AuthPromptHost], the same path as unlock. */
    private fun presentElevatedAccess(
        transmitter: RouteResultTransmitter<ElevatedAccessResult>,
        sessionKoin: Scope,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        elevatedAccessDialog.present { publish ->
            val interceptor = elevatedAccessDialog.closeOnPop()
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

    fun setElevatedAccessPassword(text: String) {
        elevatedAccessDialog.handlers.passwordOnChange?.invoke(text)
    }

    fun triggerElevatedAccessBiometric() {
        elevatedAccessDialog.handlers.onBiometric?.invoke()
    }

    fun triggerElevatedAccessYubiKey() {
        elevatedAccessDialog.handlers.onYubiKey?.invoke()
    }

    fun confirmElevatedAccess() {
        elevatedAccessDialog.handlers.onConfirm?.invoke()
    }

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
        )
    }

    fun observeServiceInfo(
        onChange: (ServiceDirectoryDetailSnapshot?) -> Unit,
    ): KeyguardCancellable = serviceInfoDialog.register(onChange)

    private fun presentServiceInfo(project: (DirectoryLinkTitles) -> ServiceDirectoryDetailSnapshot) {
        serviceInfoDialog.present { publish ->
            publish(project(directoryLinkTitles(ctx.koin.get())), Unit)
        }
    }

    fun closeServiceInfo() {
        serviceInfoDialog.close()
    }

    fun observeEmailLeak(
        onChange: (EmailLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = emailLeakDialog.register(onChange)

    /** Pushes a loading snapshot first: the producer suspends through the HIBP request before its first emission. */
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

    fun closeEmailLeak() {
        emailLeakDialog.close()
    }

    fun observePasswordLeak(
        onChange: (PasswordLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = passwordLeakDialog.register(onChange)

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

    fun closePasswordLeak() {
        passwordLeakDialog.close()
    }

    fun observeWebsiteLeak(
        onChange: (WebsiteLeakSnapshot?) -> Unit,
    ): KeyguardCancellable = websiteLeakDialog.register(onChange)

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

    fun closeWebsiteLeak() {
        websiteLeakDialog.close()
    }

    fun observeColorPicker(
        onChange: (ColorPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = colorPickerDialog.register(onChange)

    private fun presentColorPicker(
        args: ColorPickerRoute.Args,
        transmitter: RouteResultTransmitter<ColorPickerResult>,
    ) {
        val leContext = ctx.koin.get<LeContext>()
        colorPickerDialog.present { publish ->
            val title = textResource(Res.string.colorpicker_title, leContext)
            val interceptor = colorPickerDialog.closeOnPop()
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
                            argbLight = item.color.light.toArgb().toArgbLong(),
                            argbDark = item.color.dark.toArgb().toArgbLong(),
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
                        ),
                    )
                }
        }
    }

    fun selectColorPickerSwatch(id: String) {
        colorPickerDialog.handlers.onSelect[id]?.invoke()
    }

    fun confirmColorPicker() {
        colorPickerDialog.handlers.onConfirm?.invoke()
    }

    fun closeColorPicker() {
        colorPickerDialog.close()
    }

    fun observeInfoDialog(
        onChange: (InfoDialogSnapshot?) -> Unit,
    ): KeyguardCancellable = infoDialog.register(onChange)

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

    fun observeCipherLinkPicker(
        onChange: (CipherLinkPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = cipherLinkPickerDialog.register(onChange)

    private fun presentCipherLinkPicker(
        args: CipherLinkPickerRoute.Args,
        transmitter: RouteResultTransmitter<CipherLinkPickerResult>,
        sessionKoin: Scope,
    ) {
        cipherLinkPickerDialog.present { publish ->
            val interceptor = cipherLinkPickerDialog.closeOnPop()
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
                        CipherLinkPickerHandlers(state.query.onChange, handlers),
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
        cipherLinkPickerDialog.close()
    }

    fun observeAccountPicker(
        onChange: (AccountPickerSnapshot?) -> Unit,
    ): KeyguardCancellable = accountPickerDialog.register(onChange)

    private fun presentAccountPicker(
        args: OrganizationConfirmationRoute.Args,
        transmitter: RouteResultTransmitter<OrganizationConfirmationResult>,
        sessionKoin: Scope,
    ) {
        accountPickerDialog.present { publish ->
            val title = args.decor.title
            val interceptor = accountPickerDialog.closeOnPop()
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
                        ),
                    )
                }
        }
    }

    /** Sections hidden by the route flags (all but accounts for the Send form) arrive `null` and are skipped. */
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
            val interceptor = accountPickerDialog.closeOnPop()
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
                        ),
                    )
                }
        }
    }

    fun setAccountPickerNewFolderName(text: String) {
        accountPickerDialog.handlers.onNewFolderName?.invoke(text)
    }

    fun selectAccountPickerItem(key: String) {
        accountPickerDialog.handlers.onSelect[key]?.invoke()
    }

    fun confirmAccountPicker() {
        accountPickerDialog.handlers.onConfirm?.invoke()
    }

    fun closeAccountPicker() {
        accountPickerDialog.close()
    }

    fun setConfirmationFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) {
        onConfirmationFilePickerRequest = handler
    }

    fun resolveConfirmationFilePicker(requestId: String, uri: String, name: String?, size: Long) {
        val handler = confirmationFilePickerHandlers.remove(requestId) ?: return
        handler(filePickerResultOf(uri, name, size))
    }

    fun cancelConfirmationFilePicker(requestId: String) {
        val handler = confirmationFilePickerHandlers.remove(requestId) ?: return
        handler(null)
    }

    private fun handleConfirmationFilePickerIntent(intent: FilePickerIntent<*>) {
        if (closed) return
        val requestId = "cfp:${confirmationFilePickerRequestCounter++}"
        confirmationFilePickerHandlers[requestId] = intent.onFilePickerResult
        onConfirmationFilePickerRequest?.invoke(intent.toFilePickerRequest(requestId, ::AddFilePickerRequest))
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
        )
    }

    // HIBP breach dialogs: the text is resolved here because plural resolution and number formatting live in Kotlin.

    private suspend fun buildEmailLeakLoadingSnapshot(
        leContext: LeContext,
    ): EmailLeakSnapshot = EmailLeakSnapshot(
        title = textResource(Res.string.emailleak_title, leContext),
        note = textResource(Res.string.emailleak_note, leContext),
        poweredBy = hibpAttribution(leContext),
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
            poweredBy = hibpAttribution(leContext),
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
        poweredBy = hibpAttribution(leContext),
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
            poweredBy = hibpAttribution(leContext),
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
        poweredBy = hibpAttribution(leContext),
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
        poweredBy = hibpAttribution(leContext),
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

    private suspend fun hibpAttribution(
        leContext: LeContext,
    ): String = textResource(
        Res.string.powered_by_text,
        leContext,
        "[haveibeenpwned.com]($URL_HAVE_I_BEEN_PWNED)",
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
            textResource(
                Res.string.attachment_preview_error_size_limit,
                leContext,
                humanReadableByteCountBin(AttachmentPreviewLimits.MAX_ENCRYPTED_BYTES),
            )

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
