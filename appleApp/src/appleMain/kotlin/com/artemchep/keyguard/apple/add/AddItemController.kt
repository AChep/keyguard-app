package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.apple.core.sessionKoin
import com.artemchep.keyguard.main
import com.artemchep.keyguard.pick
import com.artemchep.keyguard.common.model.GetPasswordResult
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.create.CreateRequest
import com.artemchep.keyguard.common.model.create.CreateSendRequest
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.usecase.AddCipher
import com.artemchep.keyguard.common.usecase.AddSend
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.getOrNull
import com.artemchep.keyguard.common.model.titleH
import com.artemchep.keyguard.common.service.crypto.GpgKeyExpirationServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgUserIdReplacementServiceUnsupported
import com.artemchep.keyguard.common.service.crypto.GpgUserIdRevocationServiceUnsupported
import com.artemchep.keyguard.feature.add.AddStateItem
import com.artemchep.keyguard.feature.add.AddStateOwnership
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.datepicker.DatePickerResult
import com.artemchep.keyguard.feature.datepicker.DatePickerRoute
import kotlinx.datetime.Month
import com.artemchep.keyguard.feature.datedaypicker.DateDayPickerResult
import com.artemchep.keyguard.feature.datedaypicker.DateDayPickerRoute
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.filepicker.FilePickerResult
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.feature.home.vault.add.AddState
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.addCipherStateProducer
import com.artemchep.keyguard.feature.localization.textResource
import com.artemchep.keyguard.feature.navigation.NavigationIntent
import com.artemchep.keyguard.feature.navigation.RouteResultReceiver
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.send.add.SendAddRoute
import com.artemchep.keyguard.feature.send.add.sendAddStateProducer
import com.artemchep.keyguard.feature.timepicker.TimePickerResult
import com.artemchep.keyguard.feature.timepicker.TimePickerRoute
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.artemchep.keyguard.apple.core.CoreContext
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
import com.artemchep.keyguard.apple.model.invokeAction
import com.artemchep.keyguard.apple.model.toFieldSnapshot
import com.artemchep.keyguard.apple.throttleLatest
import com.artemchep.keyguard.platform.LeContext
import com.artemchep.keyguard.ui.ContextItem
import com.artemchep.keyguard.ui.FlatItemAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.koin.core.scope.Scope

/**
 * The create form for both ciphers and Sends (the Send form is a strict subset),
 * plus the file-picker bridge that surfaces a producer [FilePickerIntent] to a
 * native panel. Both shared producers expose the same create-form shape, so one
 * observer / builder serves both. The file-picker bridge ([handleFilePickerIntent])
 * is also used by the Backups screen's folder picker.
 */
internal class AddItemController(
    private val ctx: CoreContext,
) {
    /**
     * Resolves the dialog navigation interceptor handed to the add-form producers for
     * a given session DI, composed with the bridge's own [dateTimeInterceptor]. The
     * dialog interceptor catches the ownership "Save to" picker route
     * (`OrganizationConfirmationRoute`) and presents the account-picker dialog;
     * [KeyguardCore] late-binds it (it owns the [DialogController]). Defaults to no
     * dialog interceptor (only the date / time pickers are caught).
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean)? =
        { _ -> null }

    /** The common slice of AddState / SendAddState the bridge projects. */
    private class AddFormModel(
        val title: String,
        val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
        val actions: List<FlatItemAction>,
        val items: List<AddStateItem>,
        val ownership: AddStateOwnership?,
        val onSave: (() -> Unit)?,
        val merge: AddState.Merge? = null,
        /** Dropping a file anywhere on the form adds it as an attachment. */
        val fileDrag: AddState.FileDrag? = null,
    )

    private var addFieldHandlers: Map<String, (String) -> Unit> = emptyMap()
    /**
     * The revision-bumping programmatic-write sinks (the field models' `onSetText`),
     * keyed by snapshot field id, populated only for the username / password fields
     * that carry an [AddAutofillSnapshot]. Distinct from [addFieldHandlers] (the
     * `onChange` sinks): a generated value must go through `onSetText` so the field's
     * text revision advances and the SwiftUI buffer adopts it. Read by [setAddFieldText].
     */
    private var addFieldSetTextHandlers: Map<String, (String) -> Unit> = emptyMap()
    private var addSwitchHandlers: Map<String, (Boolean) -> Unit> = emptyMap()
    private var addActionHandlers: Map<String, () -> Unit> = emptyMap()
    /**
     * The TOTP "scanned QR value" sinks of the active form, keyed by the snapshot
     * item id. Each is the shared `AddStateItem.Totp.State.onScanned` closure: it
     * takes the raw scanned string (an `otpauth://` URI or a bare Base32 secret)
     * and the producer parses it into the secret / digits / algorithm fields.
     */
    private var addTotpScanHandlers: Map<String, (String) -> Unit> = emptyMap()
    private var addFormFileDropHandler: ((FilePickerResult) -> Unit)? = null
    /** Per-row file drops (replace a Send's file), keyed by the item id. */
    private var addItemFileDropHandlers: Map<String, (FilePickerResult) -> Unit> = emptyMap()
    private var addSaveHandler: (() -> Unit)? = null
    private var formIdentity: Any? = null
    private var keyTargets: Map<String, KeyTarget> = emptyMap()
    private val keyGenerator by lazy { AddKeyGeneratorController(ctx, dateTimeInterceptor) }

    private class KeyTarget(
        val kind: AddItemKind,
        val apply: (GetPasswordResult) -> Boolean,
    )

    private fun beginForm(): Any {
        keyGenerator.close()
        keyTargets = emptyMap()
        return Any().also { formIdentity = it }
    }

    private fun formObservation(identity: Any, observation: KeyguardCancellable) = KeyguardCancellable {
        observation.cancel()
        if (formIdentity === identity) {
            formIdentity = null
            keyGenerator.close()
            keyTargets = emptyMap()
        }
    }

    fun observeKeyGenerator(
        itemId: String,
        sessionId: String,
        onChange: (AddKeyGeneratorSnapshot) -> Unit,
    ): KeyguardCancellable {
        val identity = formIdentity
        val target = keyTargets[itemId]
        if (identity == null || target == null) {
            onChange(AddKeyGeneratorSnapshot.empty)
            return KeyguardCancellable {}
        }
        return keyGenerator.observe(
            id = sessionId,
            kind = target.kind,
            apply = { result ->
                formIdentity === identity && keyTargets[itemId]?.apply?.invoke(result) == true
            },
            onChange = onChange,
        )
    }

    fun invokeKeyGeneratorAction(sessionId: String, id: String) = keyGenerator.invoke(sessionId, id)
    fun setKeyGeneratorText(sessionId: String, key: String, text: String) = keyGenerator.setText(sessionId, key, text)
    fun setKeyGeneratorSwitch(sessionId: String, key: String, value: Boolean) =
        keyGenerator.setSwitch(sessionId, key, value)
    fun setKeyGeneratorCounter(sessionId: String, key: String, value: Int) =
        keyGenerator.setCounter(sessionId, key, value)
    fun useGeneratedKey(sessionId: String): Boolean = keyGenerator.use(sessionId)

    /** The ownership "Save to" account-row tap closure (opens the account picker). */
    private var addOwnershipHandler: (() -> Unit)? = null
    private var addFilePickerHandlers: MutableMap<String, (FilePickerResult?) -> Unit> = mutableMapOf()

    /** Monotonic id source for in-flight file-picker requests. */
    private var addFilePickerRequestCounter: Long = 0L

    /** Swift-registered sink for file-selection requests bubbled up from a form. */
    private var onAddFilePickerRequest: ((AddFilePickerRequest) -> Unit)? = null

    /** Monotonic id source for stashed edit-form requests. */
    private var editRequestCounter: Long = 0L

    /**
     * Full cipher / Send edit args (carrying the `initialValue` [DSecret] / [DSend])
     * stashed by the interceptor under a request id. Swift opens the edit sheet
     * keyed by that id and starts the matching observation, which threads the
     * stashed `initialValue` into the producer. The args cannot be flattened to
     * scalars for the Swift sheet (the producer needs the whole object), so they
     * are kept here instead.
     */
    private val editCipherArgs: MutableMap<String, AddRoute.Args> = mutableMapOf()
    private val editSendArgs: MutableMap<String, SendAddRoute.Args> = mutableMapOf()

    /** Swift-registered sink that presents the native edit sheet for a request id. */
    private var onEditFormRequest: ((AddEditFormRequest) -> Unit)? = null

    /** Monotonic id source for in-flight date / time picker requests. */
    private var datePickerRequestCounter: Long = 0L

    /**
     * The result transmitters of the date / time picker routes a running add form
     * emitted, stashed under a request id. Swift presents a native SwiftUI
     * `DatePicker` sheet keyed by that id and feeds the choice back through
     * [resolveAddDatePicker] / [cancelAddDatePicker], which invokes the matching
     * transmitter to drive the producer's date-time sink.
     */
    private val monthYearResultHandlers: MutableMap<String, RouteResultTransmitter<DatePickerResult>> = mutableMapOf()
    private val dateResultHandlers: MutableMap<String, RouteResultTransmitter<DateDayPickerResult>> = mutableMapOf()
    private val timeResultHandlers: MutableMap<String, RouteResultTransmitter<TimePickerResult>> = mutableMapOf()

    /** Swift-registered sink that presents the native date / time picker sheet. */
    private var onAddDatePickerRequest: ((AddDatePickerRequest) -> Unit)? = null

    /**
     * The navigation interceptor handed to the add-form producers: catches the
     * `DateDayPickerRoute` / `TimePickerRoute` a [AddStateItem.DateTime] row emits
     * (the Send custom deletion / expiration date), unwraps the result transmitter
     * and surfaces a native picker request. Every other intent is left unhandled
     * (dropped, as before). Defined lazily so it reads the live field values.
     */
    private val dateTimeInterceptor: (NavigationIntent) -> Boolean = { intent ->
        val route = (intent as? NavigationIntent.NavigateToRoute)?.route
        val holder = route as? RouteResultReceiver<*>
        when (val inner = holder?.innerRoute) {
            is DatePickerRoute -> {
                @Suppress("UNCHECKED_CAST")
                val transmitter = holder.resultTransmitter as RouteResultTransmitter<DatePickerResult>
                ctx.scope.launch {
                    val requestId = "myp:${datePickerRequestCounter++}"
                    monthYearResultHandlers[requestId] = transmitter
                    val now = nowLocalDate()
                    onAddDatePickerRequest?.invoke(
                        AddDatePickerRequest(
                            requestId = requestId,
                            kind = AddDatePickerKind.MONTH_YEAR,
                            year = inner.args.year ?: now.year,
                            month = inner.args.month ?: now.monthNumber,
                            day = 1,
                            hour = 0,
                            minute = 0,
                            minYear = now.year - 32,
                            minMonth = 1,
                            minDay = 1,
                            maxYear = now.year + 64,
                            maxMonth = 12,
                            maxDay = 31,
                            hasRange = true,
                        ),
                    )
                }
                true
            }

            is DateDayPickerRoute -> {
                @Suppress("UNCHECKED_CAST")
                val transmitter = holder.resultTransmitter as RouteResultTransmitter<DateDayPickerResult>
                // The producer dispatches its navigation intents from the background
                // pipeline; the request maps + Swift sink are main-confined, so hop.
                ctx.scope.launch { presentDatePicker(inner.args, transmitter) }
                true
            }

            is TimePickerRoute -> {
                @Suppress("UNCHECKED_CAST")
                val transmitter = holder.resultTransmitter as RouteResultTransmitter<TimePickerResult>
                ctx.scope.launch { presentTimePicker(inner.args, transmitter) }
                true
            }

            else -> false
        }
    }

    /**
     * Builds the composed interceptor for an add-form producer running with [sessionKoin]:
     * the bridge's own [dateTimeInterceptor] (date / time pickers) plus the late-bound
     * dialog interceptor (the ownership account picker). Date / time pickers take
     * precedence; whatever neither claims is dropped, as before.
     */
    /** Presents a day picker above a feature screen rather than an add form. */
    internal fun interceptDateTimePicker(intent: NavigationIntent): Boolean {
        val route = (intent as? NavigationIntent.NavigateToRoute)?.route
        val holder = route as? RouteResultReceiver<*> ?: return false
        val inner = holder.innerRoute as? DateDayPickerRoute ?: return false
        @Suppress("UNCHECKED_CAST")
        val transmitter = holder.resultTransmitter as RouteResultTransmitter<DateDayPickerResult>
        ctx.scope.launch { presentDatePicker(inner.args, transmitter, presentsInAddForm = false) }
        return true
    }

    private fun addInterceptor(
        sessionKoin: Scope,
        screenId: String,
        onClose: () -> Unit,
    ): (NavigationIntent) -> Boolean {
        val dialogInterceptor = navigationInterceptorProvider(sessionKoin)
        return { intent ->
            if (intent.closesAddForm(screenId)) {
                ctx.scope.launch { onClose() }
                true
            } else {
                dateTimeInterceptor(intent) || (dialogInterceptor?.invoke(intent) ?: false)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAddCipher(
        type: String,
        name: String? = null,
        username: String? = null,
        password: String? = null,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable {
        val cipherType = when (type) {
            "SecureNote" -> DSecret.Type.SecureNote
            "Card" -> DSecret.Type.Card
            "Identity" -> DSecret.Type.Identity
            "SshKey" -> DSecret.Type.SshKey
            "GpgKey" -> DSecret.Type.GpgKey
            else -> DSecret.Type.Login
        }
        return observeCipherForm(
            args = AddRoute.Args(
                type = cipherType,
                name = name,
                username = username,
                password = password,
            ),
            onChange = onChange,
            onClose = onClose,
        )
    }

    /**
     * Runs the edit form for the cipher [requestId] (stashed by [stashEditCipher]).
     * Threads the full `initialValue` [DSecret] into [addCipherStateProducer] so the
     * form opens pre-filled — the create/edit difference is entirely in the args.
     */
    fun observeEditCipher(
        requestId: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable {
        val args = editCipherArgs[requestId]
            ?: return unavailableEditForm(onChange)
        return observeCipherForm(args = args, onChange = onChange, onClose = onClose)
    }

    private fun unavailableEditForm(
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable {
        // An expired edit request must never turn into a new, saveable item.
        addSaveHandler = null
        onChange(AddItemFormSnapshot.empty)
        return KeyguardCancellable {}
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCipherForm(
        args: AddRoute.Args,
        onChange: (AddItemFormSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val identity = beginForm()
        val observation = ctx.launchSessionObserver(
            onLocked = {
                resetAddHandlers()
                onChange(AddItemFormSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val interceptor = addInterceptor(state.sessionKoin, "cipher_add") {
                if (producerScope.isActive) onClose()
            }
            val saveState = AddFormSaveState()
            val producerFlow = with(state.sessionKoin) {
                val addCipher = get<AddCipher>()
                ctx.koin.newHeadlessStateFlowScope("cipher_add", producerScope, interceptor)
                    .addCipherStateProducer(
                        args = args,
                        getAccounts = get(),
                        getProfiles = get(),
                        getOrganizations = get(),
                        getCollections = get(),
                        getFolders = get(),
                        getCiphers = get(),
                        getTotpCode = get(),
                        getGravatarUrl = get(),
                        getMarkdown = get(),
                        textService = get(),
                        dateFormatter = get(),
                        sshKeyImportService = get(),
                        gpgKeyImportService = get(),
                        gpgKeyEditorImportReconciler = get(),
                        gpgPublicKeyParser = get(),
                        gpgKeyExpirationService = getOrNull()
                            ?: GpgKeyExpirationServiceUnsupported,
                        gpgUserIdReplacementService = getOrNull()
                            ?: GpgUserIdReplacementServiceUnsupported,
                        gpgUserIdRevocationService = getOrNull()
                            ?: GpgUserIdRevocationServiceUnsupported,
                        logRepository = get(),
                        clipboardService = get(),
                        otpMigrationService = get(),
                        getAutofillDefaultMatchDetection = get(),
                        cipherUnsecureUrlCheck = get(),
                        showMessage = get(),
                        addCipher = object : AddCipher {
                            override fun invoke(requests: Map<String?, CreateRequest>): IO<List<String>> = {
                                saveState.run { addCipher(requests)() }
                            }
                        },
                        getAppIcons = get(),
                        getWebsiteIcons = get(),
                        confirmationRouteFactory = get(),
                    )
            }
            observeAddForm(
                producerFlow = producerFlow.map { loadable ->
                    val s = loadable.getOrNull() ?: return@map null
                    AddFormModel(
                        title = s.title,
                        filePickerIntentFlow = s.sideEffects.filePickerIntentFlow,
                        actions = s.actions,
                        items = s.items,
                        ownership = s.ownership.ui,
                        merge = s.merge,
                        onSave = s.onSave,
                        fileDrag = s.fileDrag,
                    )
                },
                leContext = leContext,
                onChange = onChange,
                saveState = saveState,
            )
        }
        return formObservation(identity, observation)
    }

    fun observeAddSend(
        type: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable {
        val sendType = when (type) {
            "File" -> DSend.Type.File
            else -> DSend.Type.Text
        }
        return observeSendForm(
            args = SendAddRoute.Args(type = sendType),
            onChange = onChange,
            onClose = onClose,
        )
    }

    /**
     * Runs the edit form for the Send [requestId] (stashed by [stashEditSend]).
     * Threads the full `initialValue` [DSend] into [sendAddStateProducer] so the
     * form opens pre-filled.
     */
    fun observeEditSend(
        requestId: String,
        onClose: () -> Unit = {},
        onChange: (AddItemFormSnapshot) -> Unit,
    ): KeyguardCancellable {
        val args = editSendArgs[requestId]
            ?: return unavailableEditForm(onChange)
        return observeSendForm(args = args, onChange = onChange, onClose = onClose)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSendForm(
        args: SendAddRoute.Args,
        onChange: (AddItemFormSnapshot) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val identity = beginForm()
        val observation = ctx.launchSessionObserver(
            onLocked = {
                resetAddHandlers()
                onChange(AddItemFormSnapshot.empty)
            },
        ) { state ->
            val producerScope = this
            val interceptor = addInterceptor(state.sessionKoin, "send_add") {
                if (producerScope.isActive) onClose()
            }
            val saveState = AddFormSaveState()
            val producerFlow = with(state.sessionKoin) {
                val addSend = get<AddSend>()
                ctx.koin.newHeadlessStateFlowScope("send_add", producerScope, interceptor)
                    .sendAddStateProducer(
                        args = args,
                        getAccounts = get(),
                        getProfiles = get(),
                        getOrganizations = get(),
                        getCollections = get(),
                        getFolders = get(),
                        getCiphers = get(),
                        getSends = get(),
                        getTotpCode = get(),
                        getGravatarUrl = get(),
                        getMarkdown = get(),
                        clipboardService = get(),
                        dateFormatter = get(),
                        addSend = object : AddSend {
                            override fun invoke(requests: Map<String?, CreateSendRequest>): IO<List<String>> = {
                                saveState.run { addSend(requests)() }
                            }
                        },
                        sendViewRouteFactory = get(),
                    )
            }
            observeAddForm(
                producerFlow = producerFlow.map { loadable ->
                    val s = loadable.getOrNull() ?: return@map null
                    AddFormModel(
                        title = s.title,
                        filePickerIntentFlow = s.sideEffects.filePickerIntentFlow,
                        actions = s.actions,
                        items = s.items,
                        ownership = s.ownership.ui,
                        onSave = s.onSave,
                    )
                },
                leContext = leContext,
                onChange = onChange,
                saveState = saveState,
            )
        }
        return formObservation(identity, observation)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeAddForm(
        producerFlow: Flow<AddFormModel?>,
        leContext: LeContext,
        onChange: (AddItemFormSnapshot) -> Unit,
        saveState: AddFormSaveState,
    ) = coroutineScope {
        val latest = MutableStateFlow<AddFormModel?>(null)
        launch {
            producerFlow.collect { latest.value = it }
        }
        launch {
            collectFilePickerIntents(latest)
        }
        latest.filterNotNull()
            .flatMapLatest { model ->
                val flows: List<Flow<Any?>> = model.items.mapNotNull { item ->
                    @Suppress("UNCHECKED_CAST")
                    (item as? AddStateItem.HasState<*, *>)?.state?.flow as Flow<Any?>?
                }
                if (flows.isEmpty()) {
                    flowOf(model)
                } else {
                    combine(flows) { model }
                }
            }
            .throttleLatest()
            .combine(saveState.running) { model, saving -> model to saving }
            .collect { (model, saving) ->
                val fieldHandlers = LinkedHashMap<String, (String) -> Unit>()
                val setTextHandlers = LinkedHashMap<String, (String) -> Unit>()
                val switchHandlers = LinkedHashMap<String, (Boolean) -> Unit>()
                val actionHandlers = LinkedHashMap<String, () -> Unit>()
                val totpScanHandlers = LinkedHashMap<String, (String) -> Unit>()
                val fileDropHandlers = LinkedHashMap<String, (FilePickerResult) -> Unit>()
                val targets = LinkedHashMap<String, KeyTarget>()
                val snapshot = buildAddItemSnapshot(
                    model,
                    leContext,
                    fieldHandlers,
                    setTextHandlers,
                    switchHandlers,
                    actionHandlers,
                    totpScanHandlers,
                    fileDropHandlers,
                    targets,
                )
                    .let { it.copy(canSave = it.canSave && !saving) }
                ctx.publishOnMain {
                    addFieldHandlers = fieldHandlers
                    addFieldSetTextHandlers = setTextHandlers
                    addSwitchHandlers = switchHandlers
                    addActionHandlers = actionHandlers
                    addTotpScanHandlers = totpScanHandlers
                    addFormFileDropHandler = model.fileDrag?.onFileDrop.takeUnless { saving }
                    addItemFileDropHandlers = fileDropHandlers.takeUnless { saving }.orEmpty()
                    keyTargets = targets.takeUnless { saving }.orEmpty()
                    addSaveHandler = model.onSave.takeUnless { saving }
                    addOwnershipHandler = model.ownership?.onClick
                    onChange(snapshot)
                }
            }
    }

    // The producer builds one stable EventFlow for its lifetime, so a single
    // subscription off the first emitted model captures every intent.
    private suspend fun collectFilePickerIntents(models: Flow<AddFormModel?>) {
        val model = models.filterNotNull().first()
        model.filePickerIntentFlow.collect { intent ->
            ctx.publishOnMain {
                handleFilePickerIntent(intent)
            }
        }
    }

    private suspend fun buildAddItemSnapshot(
        model: AddFormModel,
        leContext: LeContext,
        fieldHandlers: LinkedHashMap<String, (String) -> Unit>,
        setTextHandlers: LinkedHashMap<String, (String) -> Unit>,
        switchHandlers: LinkedHashMap<String, (Boolean) -> Unit>,
        actionHandlers: LinkedHashMap<String, () -> Unit>,
        totpScanHandlers: LinkedHashMap<String, (String) -> Unit>,
        fileDropHandlers: LinkedHashMap<String, (FilePickerResult) -> Unit>,
        keyTargets: LinkedHashMap<String, KeyTarget>,
    ): AddItemFormSnapshot {
        // The cipher's current URI context, used by the in-form username / email
        // generators (mirrors the shared AddScreen.obtainUriContext).
        val autofillUris = collectAutofillUris(model.items)
        fun textField(
            fieldId: String,
            model: TextFieldModel,
            label: String?,
            hidden: Boolean,
            multiline: Boolean,
            autofill: AddAutofillSnapshot? = null,
        ): AddTextFieldSnapshot {
            // Register the revision-bumping programmatic-write sink so the in-form
            // generator's chosen value can be pushed back through onSetText.
            if (autofill != null) {
                model.onSetText?.let { setTextHandlers[fieldId] = it }
            }
            return AddTextFieldSnapshot(
                field = model.toFieldSnapshot(fieldHandlers, id = fieldId),
                label = label,
                hidden = hidden,
                multiline = multiline,
                autofill = autofill,
            )
        }

        suspend fun actionList(
            prefix: String,
            items: List<ContextItem>,
        ): List<AddActionSnapshot> {
            val keys = ActionKeyAllocator(prefix)
            val out = ArrayList<AddActionSnapshot>()
            items.forEach { ci ->
                if (ci !is FlatItemAction) return@forEach
                val title = textResource(ci.title, leContext)
                val id = keys.keyFor(ci, title)
                ci.onClick?.let { actionHandlers[id] = it }
                out += AddActionSnapshot(
                    id = id,
                    title = title,
                    selected = ci.selected,
                )
            }
            return out
        }

        fun snapshot(
            id: String,
            kind: AddItemKind,
            title: String? = null,
            text: String? = null,
            fields: List<AddTextFieldSnapshot> = emptyList(),
            switchValue: Boolean = false,
            switchEnabled: Boolean = false,
            switchId: String? = null,
            enumValue: String? = null,
            dateText: String? = null,
            timeText: String? = null,
            attachment: AddAttachmentSnapshot? = null,
            sshKey: AddSshKeySnapshot? = null,
            gpgKey: AddGpgKeySnapshot? = null,
            passkeyName: String? = null,
            totpScanId: String? = null,
            options: List<AddActionSnapshot> = emptyList(),
            actions: List<AddActionSnapshot> = emptyList(),
        ) = AddItemSnapshot(
            id = id,
            kind = kind,
            title = title,
            text = text,
            fields = fields,
            switchValue = switchValue,
            switchEnabled = switchEnabled,
            switchId = switchId,
            enumValue = enumValue,
            dateText = dateText,
            timeText = timeText,
            attachment = attachment,
            sshKey = sshKey,
            gpgKey = gpgKey,
            passkeyName = passkeyName,
            totpScanId = totpScanId,
            options = options,
            actions = actions,
        )

        val items = model.items.map { item ->
            when (item) {
                is AddStateItem.Title<*> -> {
                    val m = item.state.flow.value
                    snapshot(item.id, AddItemKind.TITLE, fields = listOf(textField(item.id, m, null, false, false)))
                }

                is AddStateItem.Username<*> -> {
                    val st = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.USERNAME,
                        fields = listOf(
                            textField(
                                item.id, st.value, null, false, false,
                                autofill = AddAutofillSnapshot(username = true, password = false, uris = autofillUris),
                            ),
                        ),
                    )
                }

                is AddStateItem.Password<*> -> {
                    val m = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.PASSWORD,
                        title = item.label,
                        fields = listOf(
                            textField(
                                item.id, m, item.label, true, false,
                                autofill = AddAutofillSnapshot(username = false, password = true, uris = autofillUris),
                            ),
                        ),
                    )
                }

                is AddStateItem.Text<*> -> {
                    val st = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.TEXT,
                        title = st.label,
                        fields = listOf(textField(item.id, st.value, st.label, false, !st.singleLine)),
                    )
                }

                is AddStateItem.Note<*> -> {
                    val m = item.state.flow.value
                    snapshot(item.id, AddItemKind.NOTE, fields = listOf(textField(item.id, m, null, false, true)))
                }

                is AddStateItem.Totp<*> -> {
                    val st = item.state.flow.value
                    // Surface the producer's QR-scan sink so the iOS camera scanner can
                    // feed a raw `otpauth://` URI / Base32 secret back into the form; the
                    // producer parses it. Absent (null) when the form can't accept a scan.
                    val scanId = st.onScanned?.let { onScanned ->
                        val id = "${item.id}:scan"
                        totpScanHandlers[id] = onScanned
                        id
                    }
                    snapshot(
                        item.id,
                        AddItemKind.TOTP,
                        fields = listOf(textField(item.id, st.value, null, false, false)),
                        totpScanId = scanId,
                    )
                }

                is AddStateItem.Url<*> -> {
                    val st = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.URL,
                        fields = listOf(textField(item.id, st.text, null, false, false)),
                        enumValue = st.matchTypeTitle,
                        options = actionList("${item.id}:match", st.options),
                        actions = actionList("${item.id}:opt", item.options),
                    )
                }

                is AddStateItem.Field<*> -> {
                    when (val st = item.state.flow.value) {
                        is AddStateItem.Field.State.Text -> snapshot(
                            item.id,
                            AddItemKind.FIELD_TEXT,
                            fields = listOf(
                                textField("${item.id}:name", st.label, null, false, false),
                                textField("${item.id}:value", st.text, null, st.hidden, false),
                            ),
                            actions = actionList("${item.id}:opt", item.options),
                        )

                        is AddStateItem.Field.State.Switch -> {
                            st.onCheckedChange?.let { switchHandlers[item.id] = it }
                            snapshot(
                                item.id,
                                AddItemKind.FIELD_SWITCH,
                                fields = listOf(textField("${item.id}:name", st.label, null, false, false)),
                                switchValue = st.checked,
                                switchEnabled = st.onCheckedChange != null,
                                switchId = item.id,
                                actions = actionList("${item.id}:opt", item.options),
                            )
                        }

                        is AddStateItem.Field.State.LinkedId -> snapshot(
                            item.id,
                            AddItemKind.FIELD_LINKED_ID,
                            fields = listOf(textField("${item.id}:name", st.label, null, false, false)),
                            enumValue = st.value?.let { textResource(it.titleH(), leContext) },
                            options = actionList("${item.id}:linked", st.actions),
                            actions = actionList("${item.id}:opt", item.options),
                        )
                    }
                }

                is AddStateItem.Tag<*> -> {
                    when (val st = item.state.flow.value) {
                        is AddStateItem.Tag.State.Text -> snapshot(
                            item.id,
                            AddItemKind.TAG,
                            fields = listOf(textField(item.id, st.text, null, false, false)),
                            actions = actionList("${item.id}:opt", item.options),
                        )
                    }
                }

                is AddStateItem.Passkey<*> -> {
                    val pk = item.state.flow.value.passkey
                    snapshot(
                        item.id,
                        AddItemKind.PASSKEY,
                        passkeyName = pk?.userDisplayName ?: pk?.userName ?: pk?.rpName ?: pk?.rpId,
                        actions = actionList("${item.id}:opt", item.options),
                    )
                }

                is AddStateItem.Attachment<*> -> {
                    val st = item.state.flow.value
                    item.fileDrop?.let { fileDrop ->
                        fileDropHandlers[item.id] = fileDrop.onFileDrop
                    }
                    snapshot(
                        item.id,
                        AddItemKind.ATTACHMENT,
                        fields = listOf(textField(item.id, st.name, null, false, false)),
                        attachment = AddAttachmentSnapshot(
                            size = st.size,
                            synced = st.synced,
                            dropText = item.fileDrop?.text,
                        ),
                        actions = actionList("${item.id}:opt", item.options),
                    )
                }

                is AddStateItem.SshKey<*> -> {
                    val st = item.state.flow.value
                    actionHandlers["${item.id}:import"] = st.onImport
                    keyTargets[item.id] = KeyTarget(AddItemKind.SSH_KEY) { result ->
                        if (result is GetPasswordResult.AsyncKey) {
                            item.state.flow.value.onChange(result.keyPair)
                            true
                        } else {
                            false
                        }
                    }
                    val kp = st.keyPair
                    snapshot(
                        item.id,
                        AddItemKind.SSH_KEY,
                        sshKey = AddSshKeySnapshot(
                            publicKey = kp?.publicKey.orEmpty(),
                            privateKey = kp?.privateKey.orEmpty(),
                            fingerprint = kp?.fingerprint.orEmpty(),
                            hasKey = !kp?.privateKey.isNullOrBlank() || !kp?.publicKey.isNullOrBlank(),
                            canChange = true,
                        ),
                        actions = listOf(AddActionSnapshot("${item.id}:import", "", false)),
                    )
                }

                is AddStateItem.GpgKey<*> -> {
                    val st = item.state.flow.value
                    actionHandlers["${item.id}:import"] = st.onImport
                    if (st.enabled) {
                        keyTargets[item.id] = KeyTarget(AddItemKind.GPG_KEY) { result ->
                            val current = item.state.flow.value
                            if (current.enabled && result is GetPasswordResult.AsyncGpgKey) {
                                current.onChange(result.gpgKey)
                                true
                            } else {
                                false
                            }
                        }
                    }
                    val key = st.gpgKey
                    snapshot(
                        item.id,
                        AddItemKind.GPG_KEY,
                        gpgKey = AddGpgKeySnapshot(
                            publicKey = key?.publicKeyArmored.orEmpty(),
                            privateKey = key?.privateKeyArmored.orEmpty(),
                            fingerprint = key?.fingerprint.orEmpty(),
                            userId = key?.userId.orEmpty(),
                            hasKey = !key?.privateKeyArmored.isNullOrBlank() || !key?.publicKeyArmored.isNullOrBlank(),
                            canChange = st.enabled,
                        ),
                        actions = listOf(AddActionSnapshot("${item.id}:import", "", false)),
                    )
                }

                is AddStateItem.Enum<*> -> {
                    val st = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.ENUM_FIELD,
                        title = item.label,
                        enumValue = st.value,
                        options = actionList("${item.id}:enum", st.dropdown),
                    )
                }

                is AddStateItem.Switch<*> -> {
                    val m = item.state.flow.value
                    m.onChange?.let { switchHandlers[item.id] = it }
                    snapshot(
                        item.id,
                        AddItemKind.SWITCH_FIELD,
                        title = item.title,
                        text = item.text,
                        switchValue = m.checked,
                        switchEnabled = m.onChange != null,
                        switchId = item.id,
                    )
                }

                is AddStateItem.Link<*> -> {
                    val st = item.state.flow.value
                    snapshot(
                        item.id,
                        AddItemKind.LINK,
                        title = st.presentation?.title?.text,
                        text = st.presentation?.text,
                        options = actionList("${item.id}:link", item.options),
                    )
                }

                is AddStateItem.Section -> snapshot(item.id, AddItemKind.SECTION, title = item.text)

                is AddStateItem.DateMonthYear<*> -> {
                    val st = item.state.flow.value
                    actionHandlers["${item.id}:pick"] = st.onClick
                    snapshot(
                        item.id,
                        AddItemKind.DATE_MONTH_YEAR,
                        title = item.label,
                        fields = listOf(
                            textField("${item.id}:month", st.month, null, false, false),
                            textField("${item.id}:year", st.year, null, false, false),
                        ),
                        actions = listOf(AddActionSnapshot("${item.id}:pick", "", false)),
                    )
                }

                is AddStateItem.DateTime<*> -> {
                    val st = item.state.flow.value
                    actionHandlers["${item.id}:date"] = st.onSelectDate
                    actionHandlers["${item.id}:time"] = st.onSelectTime
                    snapshot(
                        item.id,
                        AddItemKind.DATE_TIME,
                        dateText = st.date,
                        timeText = st.time,
                        actions = listOf(
                            AddActionSnapshot("${item.id}:date", st.date, false),
                            AddActionSnapshot("${item.id}:time", st.time, false),
                        ),
                    )
                }

                is AddStateItem.Add -> snapshot(
                    item.id,
                    AddItemKind.ADD,
                    title = item.text,
                    options = actionList("${item.id}:add", item.actions),
                )

                is AddStateItem.Suggestion<*> -> {
                    val st = item.state.flow.value
                    val opts = st.items.mapIndexed { index, s ->
                        val id = "${item.id}:sugg:$index"
                        s.onClick?.let { actionHandlers[id] = it }
                        AddActionSnapshot(id = id, title = s.text, selected = s.selected)
                    }
                    snapshot(item.id, AddItemKind.SUGGESTION, options = opts)
                }
            }
        }

        val formActions = actionList("form", model.actions)

        // The ownership "Save to" account row: the selected account's title + email
        // come off the AddStateOwnership account element; tapping (when not read-only)
        // opens the account picker dialog through the producer's onClick.
        val ownership = model.ownership?.account?.let { account ->
            val accountItem = account.items.firstOrNull()
            AddOwnershipSnapshot(
                title = accountItem?.title.orEmpty(),
                text = accountItem?.text,
                canPick = model.ownership.onClick != null,
            )
        }

        val merge = model.merge?.let { merge ->
            val options = listOf(null) + CreateRequest.Merge.PostAction.entries
            val actions = options.map { postAction ->
                val id = "merge.postAction:${postAction?.name ?: "KEEP"}"
                merge.onChangePostAction?.let { onChange ->
                    actionHandlers[id] = { onChange(postAction) }
                }
                val title = when (postAction) {
                    null -> Res.string.additem_merge_keep_origin_ciphers_title
                    CreateRequest.Merge.PostAction.TRASH -> Res.string.additem_merge_remove_origin_ciphers_title
                    CreateRequest.Merge.PostAction.ARCHIVE -> Res.string.additem_merge_archive_origin_ciphers_title
                }
                AddActionSnapshot(
                    id = id,
                    title = textResource(title, leContext),
                    selected = postAction == merge.postAction,
                )
            }
            AddMergeSnapshot(
                sources = merge.ciphers.map { AddMergeSourceSnapshot(it.id, it.name) },
                note = merge.note?.text,
                actions = actions,
                canChange = merge.onChangePostAction != null,
            )
        }

        return AddItemFormSnapshot(
            loaded = true,
            title = model.title,
            canSave = model.onSave != null,
            ownership = ownership,
            merge = merge,
            items = items,
            actions = formActions,
            fileDropText = model.fileDrag?.text,
        )
    }

    /** Translates a producer [FilePickerIntent] into an [AddFilePickerRequest] for Swift. */
    fun handleFilePickerIntent(intent: FilePickerIntent<*>) {
        val requestId = "fp:${addFilePickerRequestCounter++}"
        @Suppress("UNCHECKED_CAST")
        val onResult = intent.onResult as (FilePickerResult?) -> Unit
        addFilePickerHandlers[requestId] = onResult
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
        onAddFilePickerRequest?.invoke(request)
    }

    /**
     * Stashes a date-picker [transmitter] under a fresh request id and asks Swift to
     * present a native day picker initialised at [DateDayPickerRoute.Args.initialDate]
     * (today when null), bounded by [DateDayPickerRoute.Args.selectableDates].
     */
    private fun presentDatePicker(
        args: DateDayPickerRoute.Args,
        transmitter: RouteResultTransmitter<DateDayPickerResult>,
        presentsInAddForm: Boolean = true,
    ) {
        val requestId = "dp:${datePickerRequestCounter++}"
        dateResultHandlers[requestId] = transmitter
        val now = nowLocalDate()
        val initial = args.initialDate ?: now
        val range = args.selectableDates
        onAddDatePickerRequest?.invoke(
            AddDatePickerRequest(
                requestId = requestId,
                kind = AddDatePickerKind.DATE,
                presentsInAddForm = presentsInAddForm,
                year = initial.year,
                month = initial.monthNumber,
                day = initial.dayOfMonth,
                hour = 0,
                minute = 0,
                minYear = range?.start?.year ?: 0,
                minMonth = range?.start?.monthNumber ?: 0,
                minDay = range?.start?.dayOfMonth ?: 0,
                maxYear = range?.endInclusive?.year ?: 0,
                maxMonth = range?.endInclusive?.monthNumber ?: 0,
                maxDay = range?.endInclusive?.dayOfMonth ?: 0,
                hasRange = range != null,
            ),
        )
    }

    /**
     * Stashes a time-picker [transmitter] under a fresh request id and asks Swift to
     * present a native time picker initialised at [TimePickerRoute.Args.initialTime]
     * (midnight when null).
     */
    private fun presentTimePicker(
        args: TimePickerRoute.Args,
        transmitter: RouteResultTransmitter<TimePickerResult>,
    ) {
        val requestId = "tp:${datePickerRequestCounter++}"
        timeResultHandlers[requestId] = transmitter
        val initial = args.initialTime ?: LocalTime(0, 0)
        onAddDatePickerRequest?.invoke(
            AddDatePickerRequest(
                requestId = requestId,
                kind = AddDatePickerKind.TIME,
                year = 0,
                month = 0,
                day = 0,
                hour = initial.hour,
                minute = initial.minute,
                minYear = 0,
                minMonth = 0,
                minDay = 0,
                maxYear = 0,
                maxMonth = 0,
                maxDay = 0,
                hasRange = false,
            ),
        )
    }

    private fun resetAddHandlers() {
        keyGenerator.close()
        keyTargets = emptyMap()
        addFieldHandlers = emptyMap()
        addFieldSetTextHandlers = emptyMap()
        addSwitchHandlers = emptyMap()
        addActionHandlers = emptyMap()
        addTotpScanHandlers = emptyMap()
        addFormFileDropHandler = null
        addItemFileDropHandlers = emptyMap()
        addSaveHandler = null
        addOwnershipHandler = null
        addFilePickerHandlers.clear()
        monthYearResultHandlers.clear()
        dateResultHandlers.clear()
        timeResultHandlers.clear()
        editCipherArgs.clear()
        editSendArgs.clear()
    }

    /**
     * Collects the cipher's current URI context for the in-form username / email
     * generators, mirroring the shared `AddScreen.obtainUriContext`: every
     * [AddStateItem.Url] item's raw url text, except those whose match type is a
     * regular expression (which would be a poor generator hint). Returns a plain
     * [List] for the Swift bridge.
     */
    private fun collectAutofillUris(items: List<AddStateItem>): List<String> =
        items.mapNotNull { item ->
            if (item !is AddStateItem.Url<*>) return@mapNotNull null
            val state = item.state.flow.value
            if (state.matchType == DSecret.Uri.MatchType.RegularExpression) {
                return@mapNotNull null
            }
            state.text.text
        }

    /** Writes [text] into an add-form text field identified by its snapshot field id. */
    fun setAddField(id: String, text: String) {
        addFieldHandlers[id]?.invoke(text)
    }

    /**
     * Writes [text] into an add-form text field through its revision-bumping
     * programmatic-write sink (the field model's `onSetText`), used by the in-form
     * Autofill / generator to push a generated value into the username / password
     * field. Distinct from [setAddField], which routes through `onChange` and does
     * NOT advance the text revision — so the SwiftUI buffer would ignore it. Only
     * the username / password fields register a sink here.
     */
    fun setAddFieldText(id: String, text: String) {
        addFieldSetTextHandlers[id]?.invoke(text)
    }

    /** Toggles an add-form switch identified by its [AddItemSnapshot.switchId]. */
    fun setAddSwitch(id: String, value: Boolean) {
        addSwitchHandlers[id]?.invoke(value)
    }

    /** Invokes an add-form `() -> Unit` closure by its snapshot action id. */
    fun invokeAddAction(id: String) {
        addActionHandlers.invokeAction(id)
    }

    /**
     * Feeds a scanned QR payload into the TOTP item identified by its
     * [AddItemSnapshot.totpScanId]. [value] is the raw scanned string (an
     * `otpauth://` URI or a bare Base32 secret); the shared producer parses it.
     */
    fun scanAddTotp(id: String, value: String) {
        addTotpScanHandlers[id]?.invoke(value)
    }

    /** Submits the active create form. */
    fun submitAddItem() {
        addSaveHandler?.invoke()
    }

    /**
     * Opens the ownership "Save to" account picker of the active form. Fires the
     * producer's ownership `onClick`, which emits an `OrganizationConfirmationRoute`
     * caught by the add-form interceptor and presented as the account-picker dialog.
     * No-op when the form's account is read-only.
     */
    fun invokeAddOwnership() {
        addOwnershipHandler?.invoke()
    }

    /** Registers the SwiftUI sink that presents a native file panel for intents. */
    fun setAddFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) {
        onAddFilePickerRequest = handler
    }

    /** Registers the SwiftUI sink that presents the native edit sheet for a request. */
    fun setEditFormRequestHandler(handler: ((AddEditFormRequest) -> Unit)?) {
        onEditFormRequest = handler
    }

    /**
     * Stashes full [AddRoute.Args] under a fresh request id and asks Swift to open
     * the form. Used for edits, clones, and generated keys whose structured values
     * cannot be represented by the scalar create callback. The args determine
     * whether the producer creates a new cipher or edits an existing one.
     */
    fun stashEditCipher(args: AddRoute.Args) {
        val requestId = "edit:${editRequestCounter++}"
        editCipherArgs[requestId] = args
        onEditFormRequest?.invoke(AddEditFormRequest(requestId = requestId, isSend = false))
    }

    /**
     * Stashes a Send edit [SendAddRoute.Args] (carrying its `initialValue` [DSend])
     * under a fresh request id and asks Swift to open the edit sheet. Called from
     * the navigation interceptor for a producer-emitted `SendAddRoute`.
     */
    fun stashEditSend(args: SendAddRoute.Args) {
        val requestId = "edit:${editRequestCounter++}"
        editSendArgs[requestId] = args
        onEditFormRequest?.invoke(AddEditFormRequest(requestId = requestId, isSend = true))
    }

    /** Drops the stashed edit args for [requestId] once its sheet is dismissed. */
    fun clearEditForm(requestId: String) {
        editCipherArgs.remove(requestId)
        editSendArgs.remove(requestId)
    }

    /** Feeds a chosen file back into the producer continuation for [requestId]. */
    fun resolveAddFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String? = null) {
        val handler = addFilePickerHandlers.remove(requestId) ?: return
        handler(filePickerResultOf(uri, name, size, accessToken))
    }

    /** Adds a file dropped onto the form as an attachment. No-op unless [AddItemFormSnapshot.fileDropText]. */
    fun dropFileOnAddForm(uri: String, name: String?, size: Long) {
        addFormFileDropHandler?.invoke(filePickerResultOf(uri, name, size))
    }

    /** Feeds a file dropped onto the row [itemId]. No-op unless its [AddAttachmentSnapshot.dropText]. */
    fun dropFileOnAddItem(itemId: String, uri: String, name: String?, size: Long) {
        addItemFileDropHandlers[itemId]?.invoke(filePickerResultOf(uri, name, size))
    }

    /** Cancels an in-flight file-picker request for [requestId]. */
    fun cancelAddFilePicker(requestId: String) {
        val handler = addFilePickerHandlers.remove(requestId) ?: return
        handler(null)
    }

    /** Registers the SwiftUI sink that presents the native date / time picker sheet. */
    fun setAddDatePickerRequestHandler(handler: ((AddDatePickerRequest) -> Unit)?) {
        onAddDatePickerRequest = handler
    }

    /**
     * Confirms an in-flight date / time picker [requestId] with the chosen value,
     * driving the producer's date-time sink (the picked date keeps the time, or
     * vice-versa — the producer's onSelectDate / onSelectTime closures merge them).
     * Date requests read [year] / [month] / [day]; time requests read [hour] / [minute].
     */
    fun resolveAddDatePicker(requestId: String, year: Int, month: Int, day: Int, hour: Int, minute: Int) {
        monthYearResultHandlers.remove(requestId)?.let { transmitter ->
            transmitter(DatePickerResult.Confirm(month = Month.entries[month - 1], year = year))
            return
        }
        dateResultHandlers.remove(requestId)?.let { transmitter ->
            transmitter(DateDayPickerResult.Confirm(localDate = LocalDate(year, month, day)))
            return
        }
        timeResultHandlers.remove(requestId)?.let { transmitter ->
            transmitter(TimePickerResult.Confirm(localTime = LocalTime(hour, minute)))
        }
    }

    /** Cancels an in-flight date / time picker request for [requestId]. */
    fun cancelAddDatePicker(requestId: String) {
        monthYearResultHandlers.remove(requestId)?.invoke(DatePickerResult.Deny)
        dateResultHandlers.remove(requestId)?.invoke(DateDayPickerResult.Deny)
        timeResultHandlers.remove(requestId)?.invoke(TimePickerResult.Deny)
    }

    /** Today in the system time zone (the day-picker fallback when no initial date). */
    private fun nowLocalDate(): LocalDate =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
}
