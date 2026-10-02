package com.artemchep.keyguard.apple.add

import com.artemchep.keyguard.apple.core.sessionKoin
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
import kotlinx.coroutines.CoroutineScope
import com.artemchep.keyguard.apple.core.launchOnMainWhileActive
import com.artemchep.keyguard.apple.core.KeyguardCancellable
import com.artemchep.keyguard.apple.core.collectOnMain
import com.artemchep.keyguard.apple.core.headlessScreenId
import com.artemchep.keyguard.apple.core.newHeadlessStateFlowScope
import com.artemchep.keyguard.apple.core.resultRouteOrNull
import com.artemchep.keyguard.apple.core.filePickerResultOf
import com.artemchep.keyguard.apple.core.onFilePickerResult
import com.artemchep.keyguard.apple.core.toFilePickerRequest
import com.artemchep.keyguard.apple.model.ActionKeyAllocator
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
import org.koin.core.scope.Scope
import kotlin.uuid.Uuid

/**
 * One observer and builder serve both the cipher and Send forms (the Send form is a strict subset).
 */
internal class AddItemController(
    private val ctx: CoreContext,
) {
    /**
     * The session's dialog interceptor (pickers and every other dialog route). `KeyguardCore` late-binds it;
     * the default catches nothing, so only date / time pickers are handled.
     */
    var navigationInterceptorProvider: (Scope) -> ((NavigationIntent) -> Boolean)? =
        { _ -> null }

    private val ownerId = Uuid.random().toString()

    private class AddFormModel(
        val title: String,
        val filePickerIntentFlow: Flow<FilePickerIntent<*>>,
        val actions: List<FlatItemAction>,
        val items: List<AddStateItem>,
        val ownership: AddStateOwnership?,
        val onSave: (() -> Unit)?,
        val merge: AddState.Merge? = null,
        val fileDrag: AddState.FileDrag? = null,
    )

    private val keyGenerator by lazy { AddKeyGeneratorController(ctx, dateTimeInterceptor) }

    fun observeKeyGenerator(
        sessionId: String,
        kind: AddItemKind,
        apply: (GetPasswordResult) -> Boolean,
        onChange: (AddKeyGeneratorSnapshot) -> Unit,
    ): KeyguardCancellable = keyGenerator.observe(id = sessionId, kind = kind, apply = apply, onChange = onChange)

    fun invokeKeyGeneratorAction(sessionId: String, id: String) = keyGenerator.invoke(sessionId, id)
    fun setKeyGeneratorText(sessionId: String, key: String, text: String) = keyGenerator.setText(sessionId, key, text)
    fun setKeyGeneratorSwitch(sessionId: String, key: String, value: Boolean) =
        keyGenerator.setSwitch(sessionId, key, value)
    fun setKeyGeneratorCounter(sessionId: String, key: String, value: Int) =
        keyGenerator.setCounter(sessionId, key, value)
    fun useGeneratedKey(sessionId: String): Boolean = keyGenerator.use(sessionId)

    private var addFilePickerHandlers: MutableMap<String, (FilePickerResult?) -> Unit> = mutableMapOf()

    private var addFilePickerRequestCounter: Long = 0L

    private var onAddFilePickerRequest: ((AddFilePickerRequest) -> Unit)? = null

    private var editRequestCounter: Long = 0L

    /** Whole form args (edits, clones, generated keys) that can't be flattened for Swift, keyed by request id. */
    private val editCipherArgs: MutableMap<String, AddRoute.Args> = mutableMapOf()
    private val editSendArgs: MutableMap<String, SendAddRoute.Args> = mutableMapOf()

    private var onEditFormRequest: ((AddEditFormRequest) -> Unit)? = null

    private var datePickerRequestCounter: Long = 0L

    private val monthYearResultHandlers: MutableMap<String, RouteResultTransmitter<DatePickerResult>> = mutableMapOf()
    private val dateResultHandlers: MutableMap<String, RouteResultTransmitter<DateDayPickerResult>> = mutableMapOf()
    private val timeResultHandlers: MutableMap<String, RouteResultTransmitter<TimePickerResult>> = mutableMapOf()

    private var onAddDatePickerRequest: ((AddDatePickerRequest) -> Unit)? = null

    /**
     * Catches [DatePickerRoute] (a card's month / year), [DateDayPickerRoute] and [TimePickerRoute], stashes the
     * result transmitter and sends a native picker request to Swift.
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

    /** Presents a day picker above a feature screen rather than an add form. */
    internal fun interceptDateTimePicker(intent: NavigationIntent): Boolean {
        val (route, transmitter) = intent.resultRouteOrNull<DateDayPickerRoute, DateDayPickerResult>()
            ?: return false
        ctx.scope.launch { presentDatePicker(route.args, transmitter, presentsInAddForm = false) }
        return true
    }

    /**
     * Closes the form when it pops its own [screenId], then tries [dateTimeInterceptor], then the dialog
     * interceptor from [navigationInterceptorProvider]; anything else is dropped.
     */
    private fun CoroutineScope.addInterceptor(
        sessionKoin: Scope,
        screenId: String,
        onClose: () -> Unit,
    ): (NavigationIntent) -> Boolean {
        val dialogInterceptor = navigationInterceptorProvider(sessionKoin)
        return { intent ->
            if (intent.closesAddForm(screenId)) {
                launchOnMainWhileActive(ctx.scope, onClose)
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
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
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
            publish = publish,
            onClose = onClose,
        )
    }

    fun observeEditCipher(
        requestId: String,
        onClose: () -> Unit = {},
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
    ): KeyguardCancellable {
        val args = editCipherArgs[requestId]
            ?: return unavailableEditForm(publish)
        return observeCipherForm(args = args, publish = publish, onClose = onClose)
    }

    private fun unavailableEditForm(
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
    ): KeyguardCancellable {
        // An expired edit request must never turn into a new, saveable item.
        publish(AddItemFormSnapshot.empty, AddFormActions())
        return KeyguardCancellable {}
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeCipherForm(
        args: AddRoute.Args,
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val screenId = headlessScreenId("cipher_add", ownerId)
        return ctx.launchSessionObserver(onLocked = onClose) { state ->
            val producerScope = this
            val interceptor = addInterceptor(state.sessionKoin, screenId, onClose)
            val saveState = AddFormSaveState()
            val producerFlow = with(state.sessionKoin) {
                val addCipher = get<AddCipher>()
                ctx.koin.newHeadlessStateFlowScope("cipher_add", producerScope, interceptor, instanceId = ownerId)
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
                publish = publish,
                saveState = saveState,
            )
        }
    }

    fun observeAddSend(
        type: String,
        onClose: () -> Unit = {},
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
    ): KeyguardCancellable {
        val sendType = when (type) {
            "File" -> DSend.Type.File
            else -> DSend.Type.Text
        }
        return observeSendForm(
            args = SendAddRoute.Args(type = sendType),
            publish = publish,
            onClose = onClose,
        )
    }

    fun observeEditSend(
        requestId: String,
        onClose: () -> Unit = {},
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
    ): KeyguardCancellable {
        val args = editSendArgs[requestId]
            ?: return unavailableEditForm(publish)
        return observeSendForm(args = args, publish = publish, onClose = onClose)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeSendForm(
        args: SendAddRoute.Args,
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
        onClose: () -> Unit,
    ): KeyguardCancellable {
        val leContext = ctx.koin.get<LeContext>()
        val screenId = headlessScreenId("send_add", ownerId)
        return ctx.launchSessionObserver(onLocked = onClose) { state ->
            val producerScope = this
            val interceptor = addInterceptor(state.sessionKoin, screenId, onClose)
            val saveState = AddFormSaveState()
            val producerFlow = with(state.sessionKoin) {
                val addSend = get<AddSend>()
                ctx.koin.newHeadlessStateFlowScope("send_add", producerScope, interceptor, instanceId = ownerId)
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
                publish = publish,
                saveState = saveState,
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeAddForm(
        producerFlow: Flow<AddFormModel?>,
        leContext: LeContext,
        publish: (AddItemFormSnapshot, AddFormActions) -> Unit,
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
            .map { (model, saving) ->
                val fieldHandlers = LinkedHashMap<String, (String) -> Unit>()
                val setTextHandlers = LinkedHashMap<String, (String) -> Unit>()
                val switchHandlers = LinkedHashMap<String, (Boolean) -> Unit>()
                val actionHandlers = LinkedHashMap<String, () -> Unit>()
                val totpScanHandlers = LinkedHashMap<String, (String) -> Unit>()
                val fileDropHandlers = LinkedHashMap<String, (FilePickerResult) -> Unit>()
                val targets = LinkedHashMap<String, AddKeyTarget>()
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
                // Saving freezes everything that could edit or resubmit the form.
                snapshot to AddFormActions(
                    fields = fieldHandlers,
                    setText = setTextHandlers,
                    switches = switchHandlers,
                    actions = actionHandlers,
                    totpScan = totpScanHandlers,
                    formFileDrop = model.fileDrag?.onFileDrop.takeUnless { saving },
                    itemFileDrop = fileDropHandlers.takeUnless { saving }.orEmpty(),
                    keyTargets = targets.takeUnless { saving }.orEmpty(),
                    save = model.onSave.takeUnless { saving },
                    ownership = model.ownership?.onClick,
                )
            }
            .collectOnMain { (snapshot, actions) -> publish(snapshot, actions) }
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
        keyTargets: LinkedHashMap<String, AddKeyTarget>,
    ): AddItemFormSnapshot {
        val autofillUris = collectAutofillUris(model.items)
        fun textField(
            fieldId: String,
            model: TextFieldModel,
            label: String?,
            hidden: Boolean,
            multiline: Boolean,
            autofill: AddAutofillSnapshot? = null,
        ): AddTextFieldSnapshot {
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
                    keyTargets[item.id] = AddKeyTarget(AddItemKind.SSH_KEY) { result ->
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
                        keyTargets[item.id] = AddKeyTarget(AddItemKind.GPG_KEY) { result ->
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

    fun handleFilePickerIntent(intent: FilePickerIntent<*>) {
        val requestId = "fp:${addFilePickerRequestCounter++}"
        addFilePickerHandlers[requestId] = intent.onFilePickerResult
        onAddFilePickerRequest?.invoke(intent.toFilePickerRequest(requestId, ::AddFilePickerRequest))
    }

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
        addFilePickerHandlers.clear()
        monthYearResultHandlers.clear()
        dateResultHandlers.clear()
        timeResultHandlers.clear()
        editCipherArgs.clear()
        editSendArgs.clear()
    }

    /**
     * The URI context of the in-form username / email generators, as in the shared `AddScreen.obtainUriContext`;
     * regular-expression urls are skipped as poor generator hints.
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

    fun setAddFilePickerRequestHandler(handler: ((AddFilePickerRequest) -> Unit)?) {
        onAddFilePickerRequest = handler
    }

    fun setEditFormRequestHandler(handler: ((AddEditFormRequest) -> Unit)?) {
        onEditFormRequest = handler
    }

    fun stashEditCipher(args: AddRoute.Args) {
        val requestId = "edit:${editRequestCounter++}"
        editCipherArgs[requestId] = args
        onEditFormRequest?.invoke(AddEditFormRequest(requestId = requestId, isSend = false))
    }

    fun stashEditSend(args: SendAddRoute.Args) {
        val requestId = "edit:${editRequestCounter++}"
        editSendArgs[requestId] = args
        onEditFormRequest?.invoke(AddEditFormRequest(requestId = requestId, isSend = true))
    }

    /** Copies immutable route arguments into the presentation that owns the producer. */
    fun copyEditRequest(requestId: String, destination: AddItemController) {
        editCipherArgs[requestId]?.let { destination.editCipherArgs[requestId] = it }
        editSendArgs[requestId]?.let { destination.editSendArgs[requestId] = it }
    }

    /** A closed form controller presents no more pickers: its Swift sinks are gone. */
    fun close() {
        resetAddHandlers()
        onAddFilePickerRequest = null
        onAddDatePickerRequest = null
    }

    fun clearEditForm(requestId: String) {
        editCipherArgs.remove(requestId)
        editSendArgs.remove(requestId)
    }

    fun resolveAddFilePicker(requestId: String, uri: String, name: String?, size: Long, accessToken: String? = null) {
        val handler = addFilePickerHandlers.remove(requestId) ?: return
        handler(filePickerResultOf(uri, name, size, accessToken))
    }

    fun cancelAddFilePicker(requestId: String) {
        val handler = addFilePickerHandlers.remove(requestId) ?: return
        handler(null)
    }

    fun setAddDatePickerRequestHandler(handler: ((AddDatePickerRequest) -> Unit)?) {
        onAddDatePickerRequest = handler
    }

    /**
     * Date requests read [year] / [month] / [day]; time requests read [hour] / [minute]. The producer keeps the
     * other half: a picked date keeps the field's time, and vice versa.
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

    fun cancelAddDatePicker(requestId: String) {
        monthYearResultHandlers.remove(requestId)?.invoke(DatePickerResult.Deny)
        dateResultHandlers.remove(requestId)?.invoke(DateDayPickerResult.Deny)
        timeResultHandlers.remove(requestId)?.invoke(TimePickerResult.Deny)
    }

    private fun nowLocalDate(): LocalDate =
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
}

/** A key item's generator target: what to generate, and how a result lands in the form. */
internal class AddKeyTarget(
    val kind: AddItemKind,
    val apply: (GetPasswordResult) -> Boolean,
)

/** One add form's callbacks, published atomically with the snapshot they were built for. */
internal class AddFormActions(
    val fields: Map<String, (String) -> Unit> = emptyMap(),
    val setText: Map<String, (String) -> Unit> = emptyMap(),
    val switches: Map<String, (Boolean) -> Unit> = emptyMap(),
    val actions: Map<String, () -> Unit> = emptyMap(),
    val totpScan: Map<String, (String) -> Unit> = emptyMap(),
    val formFileDrop: ((FilePickerResult) -> Unit)? = null,
    val itemFileDrop: Map<String, (FilePickerResult) -> Unit> = emptyMap(),
    val keyTargets: Map<String, AddKeyTarget> = emptyMap(),
    val save: (() -> Unit)? = null,
    val ownership: (() -> Unit)? = null,
)
