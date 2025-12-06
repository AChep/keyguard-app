package com.artemchep.keyguard.android.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.autofill.inline.v1.InlineSuggestionUi.Content
import arrow.core.*
import arrow.optics.Getter
import com.artemchep.keyguard.android.AutofillActivity
import com.artemchep.keyguard.android.AutofillFakeAuthActivity
import com.artemchep.keyguard.android.AutofillSaveActivity
import com.artemchep.keyguard.android.MainActivity
import com.artemchep.keyguard.android.PendingIntents
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.*
import com.artemchep.keyguard.common.model.*
import com.artemchep.keyguard.common.service.logging.LogRepository
import com.artemchep.keyguard.common.service.logging.postDebug
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.feature.home.vault.component.FormatCardGroupLength
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.collections.take
import kotlin.coroutines.CoroutineContext

class KeyguardAutofillService : AutofillService(), DIAware {
    companion object {
        private const val TAG = "AFService"

        const val KEEP_CIPHERS_IN_MEMORY_FOR = 10_000L

        const val SUGGESTIONS_MAX_COUNT = 10
    }

    private val job = Job()

    private val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = Dispatchers.Main + job
    }

    override val di by closestDI { this }

    private sealed interface Vault {
        class Open(
            val ciphers: List<DSecret>,
            val getSuggestions: GetSuggestions<DSecret>,
            val equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
        ) : Vault

        data object Locked : Vault
    }

    private suspend fun Vault.Open.getSuggestions(
        autofillTarget: AutofillTarget,
    ): List<DSecret> {
        return getSuggestions(
            ciphers,
            Getter { it },
            autofillTarget,
            equivalentDomainsBuilderFactory,
        ).bind().take(SUGGESTIONS_MAX_COUNT)
    }

    private fun getVaultOpenFlow(
        session: MasterSession.Key,
    ): Flow<Vault.Open> {
        val getCiphers = session.di.direct.instance<GetCiphers>()
        val getProfiles = session.di.direct.instance<GetProfiles>()
        val getSuggestions = kotlin.run {
            val model = session.di.direct
                .instance<GetSuggestions<Any?>>()
            GetCipherSuggestions(model)
        }

        val equivalentDomainsBuilderFactory =
            session.di.direct.instance<EquivalentDomainsBuilderFactory>()

        val ciphersRawFlow = filterHiddenProfiles(
            getProfiles = getProfiles,
            getCiphers = getCiphers,
            filter = null,
        )
        val ciphersFlow = ciphersRawFlow
            .map { ciphers ->
                val filteredCiphers = ciphers
                    .filter { !it.deleted }
                filteredCiphers
            }
        return ciphersFlow
            .map { ciphers ->
                Vault.Open(
                    ciphers = ciphers,
                    getSuggestions = getSuggestions,
                    equivalentDomainsBuilderFactory = equivalentDomainsBuilderFactory,
                )
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val vaultFlow by lazy {
        val model: GetVaultSession by di.instance()
        model()
            .distinctUntilChanged()
            .flatMapLatest { session ->
                when (session) {
                    is MasterSession.Key -> getVaultOpenFlow(session)

                    is MasterSession.Empty -> {
                        val v = Vault.Locked
                        flowOf(v)
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .shareIn(
                scope,
                started = SharingStarted.WhileSubscribed(KEEP_CIPHERS_IN_MEMORY_FOR),
                replay = 1,
            )
    }

    private val logRepository: LogRepository by lazy {
        di.direct.instance()
    }

    private val getTotpCode: GetTotpCode by lazy {
        di.direct.instance()
    }

    private val blockedUrlCheck: BlockedUrlCheck by lazy {
        di.direct.instance()
    }

    private val prefDefaultMatchDetectionFlow by lazy {
        val model: GetAutofillDefaultMatchDetection by di.instance()
        model()
    }

    private val prefInlineSuggestionsFlow by lazy {
        val model: GetAutofillInlineSuggestions by di.instance()
        model()
    }

    private val prefManualSelectionFlow by lazy {
        val model: GetAutofillManualSelection by di.instance()
        model()
    }

    private val prefRespectAutofillOffFlow by lazy {
        val model: GetAutofillRespectAutofillOff by di.instance()
        model()
    }

    private val prefSaveRequestFlow by lazy {
        val model: GetAutofillSaveRequest by di.instance()
        model()
    }

    private val prefBlockedUrisFlow by lazy {
        val model: GetAutofillBlockedUrisExposed by di.instance()
        model()
    }

    private val autofillStructureParser = AutofillStructureParser()

    private class GetCipherSuggestions(
        private val parent: GetSuggestions<Any?>,
    ) : GetSuggestions<DSecret> {
        override fun invoke(
            ciphers: List<DSecret>,
            getter: Getter<DSecret, DSecret>,
            target: AutofillTarget,
            equivalentDomainsBuilderFactory: EquivalentDomainsBuilderFactory,
        ): IO<List<DSecret>> = parent
            .invoke(
                ciphers,
                Getter { it as DSecret },
                target,
                equivalentDomainsBuilderFactory,
            ) as IO<List<DSecret>>
    }

    private class AbortAutofillException(
        message: String,
    ) : RuntimeException(message)

    @SuppressLint("NewApi")
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        getAutofillStructureIo(request)
            .effectMap { autofillStructure ->
                if (autofillStructure.items.isEmpty()) {
                    throw AbortAutofillException("Nothing to autofill.")
                }

                autofillStructure
            }
            .effectMap { autofillStructure ->
                val autofillTarget = autofillStructure.toAutofillTarget()
                val autofillTargetUris = autofillTarget
                    .links
                    .mapNotNull { link ->
                        when (link) {
                            is LinkInfoPlatform.Android -> link.uri
                            is LinkInfoPlatform.Web -> link.url
                                .toString()
                            else -> null
                        }
                    }
                // Check if any of the target URIs match
                // any of the blocked ones.
                val shouldBlock = combineIo(
                    prefBlockedUrisFlow
                        .toIO(),
                    prefDefaultMatchDetectionFlow
                        .toIO(),
                ) { blockedUris, defaultMatchDetection ->
                    // Since the vault is locked, we can not get the equivalent domains
                    // list. Later it will be nice to have it as a part of 'exposed' database
                    // by for now we just ignore it.
                    val equivalentDomains = EquivalentDomains(domains = emptyMap())
                    blockedUris.any { blockedUri ->
                        blockedUri.enabled && autofillTargetUris.any { targetUri ->
                            blockedUrlCheck.invoke(
                                blockedUri,
                                targetUri,
                                defaultMatchDetection,
                                equivalentDomains,
                            ).bind()
                        }
                    }
                }.bind()
                if (shouldBlock) {
                    throw AbortAutofillException("Blocked autofill.")
                }

                autofillStructure
            }
            .flatMap { autofillStructure ->
                getAutofillResponseIo(
                    request = request,
                    autofillStructure = autofillStructure,
                )
            }
            .effectTap { response ->
                callback.onSuccess(response)
            }
            .handleError { e ->
                logRepository.postDebug(TAG) {
                    "Fill request: aborted because '$e'"
                }

                if (cancellationSignal.isCanceled) {
                    return@handleError
                }

                val msg = e.message ?: "Something went wrong"
                callback.onFailure(msg)
            }
            .dispatchOn(Dispatchers.Main.immediate)
            .launchIn(scope)
        cancellationSignal.setOnCancelListener {
            job.cancel()
        }
    }

    private fun getAutofillStructureIo(
        request: FillRequest,
    ) = ioEffect {
        val assistStructureLatest = request.fillContexts
            .map { it.structure }
            .lastOrNull()
        if (assistStructureLatest == null) {
            throw AbortAutofillException("No structures to fill.")
        }

        val respectAutofillOff = prefRespectAutofillOffFlow.first()
        autofillStructureParser.parse(
            assistStructureLatest,
            respectAutofillOff,
        )
    }

    private fun getSaveStructureIo(
        request: SaveRequest,
    ) = ioEffect {
        val assistStructureLatest = request.fillContexts
            .map { it.structure }
            .lastOrNull()
        if (assistStructureLatest == null) {
            throw AbortAutofillException("No structures to save.")
        }

        val respectAutofillOff = prefRespectAutofillOffFlow.first()
        autofillStructureParser.parse(
            assistStructureLatest,
            respectAutofillOff,
        )
    }

    private fun getAutofillResponseIo(
        request: FillRequest,
        autofillStructure: AutofillStructure2,
    ) = vaultFlow
        .toIO()
        .effectMap(Dispatchers.Default) { state ->
            val autofillTarget = autofillStructure.toAutofillTarget()
            when (state) {
                is Vault.Open -> {
                    // Get the suggested items only
                    val suggestedItems = state.getSuggestions(autofillTarget)
                    suggestedItems.right()
                }
                is Vault.Locked -> {
                    Unit.left()
                }
            }
        }
        .effectMap { r ->
            val shouldInlineSuggestions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    prefInlineSuggestionsFlow.toIO()
                        .attempt().bind().isRight { it }

            val forceHideManualSelection = kotlin.run {
                val targetApplicationId = autofillStructure.applicationId
                val keyguardApplicationId = packageName
                targetApplicationId == keyguardApplicationId
            }

            // build a response
            val responseBuilder = FillResponse.Builder()
            when (r) {
                is Either.Left -> {
                    if (forceHideManualSelection) {
                        throw AbortAutofillException("Can not autofill own app password.")
                    }
                    // Database is locked, create a generic
                    // sign in with option.
                    responseBuilder.buildAuthentication(
                        type = AuthenticationType.UNLOCK,
                        struct = autofillStructure,
                        request = request,
                        canInlineSuggestions = shouldInlineSuggestions,
                    )
                }

                is Either.Right -> if (r.value.isEmpty()) {
                    if (forceHideManualSelection) {
                        throw AbortAutofillException("No match found.")
                    }
                    // No match found, create a generic option.
                    responseBuilder.buildAuthentication(
                        type = AuthenticationType.SELECT,
                        struct = autofillStructure,
                        request = request,
                        canInlineSuggestions = shouldInlineSuggestions,
                    )
                } else {
                    val manualSelection = prefManualSelectionFlow.toIO().bind() &&
                            !forceHideManualSelection

                    val totalInlineSuggestionsMaxCount = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        shouldInlineSuggestions
                    ) {
                        request.inlineSuggestionsRequest?.maxSuggestionCount
                            ?: 0 // no suggestions allowed
                    } else {
                        0
                    }
                    val secretInlineSuggestionsMaxCount =
                        if (manualSelection) totalInlineSuggestionsMaxCount - 1 else totalInlineSuggestionsMaxCount

                    var index = 0
                    r.value.forEach { secret ->
                        var datasetHasInlinePresentation = false
                        val dataset = tryBuildDataset(
                            context = this,
                            secret = secret,
                            struct = autofillStructure,
                            provideInlinePresentation = {
                                if (index < secretInlineSuggestionsMaxCount) {
                                    tryBuildSecretInlinePresentation(
                                        request,
                                        index,
                                        secret,
                                    )?.also {
                                        datasetHasInlinePresentation = true
                                    }
                                } else {
                                    null
                                }
                            },
                        )
                        if (dataset != null && (!shouldInlineSuggestions || datasetHasInlinePresentation)) {
                            responseBuilder.addDataset(dataset)
                            index += 1
                        }
                    }
                    if (manualSelection) {
                        val intent = AutofillActivity.getIntent(
                            context = this@KeyguardAutofillService,
                            args = AutofillActivity.Args(
                                applicationId = autofillStructure.applicationId,
                                webDomain = autofillStructure.webDomain,
                                webScheme = autofillStructure.webScheme,
                                autofillStructure2 = autofillStructure,
                            ),
                        )

                        val flags =
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        val code = PendingIntents.autofill.obtainId()
                        val pi = PendingIntent.getActivity(this, code, intent, flags)

                        val manualSelectionView = AutofillViews
                            .buildPopupEntryManual(this)

                        autofillStructure.items.forEach {
                            val autofillId = it.id
                            val builder = DatasetBuilder.create(
                                menuPresentation = manualSelectionView,
                                fields = mapOf(
                                    autofillId to null,
                                ),
                                provideInlinePresentation = {
                                    if (totalInlineSuggestionsMaxCount <= 0) {
                                        return@create null
                                    }

                                    tryBuildManualSelectionInlinePresentation(
                                        request,
                                        index,
                                        intent = intent,
                                    )
                                },
                            )
                            builder.setAuthentication(pi.intentSender)
                            responseBuilder.addDataset(builder.build())
                        }
                    }
                }
            }
            val shouldSaveRequest = prefSaveRequestFlow.first() &&
                    !forceHideManualSelection
            if (shouldSaveRequest) {
                class SaveItem(
                    val flag: Int,
                    val item: AutofillStructure2.Item,
                )

                val saveItems = mutableListOf<SaveItem>()
                autofillStructure.items
                    .distinctBy { it.hint }
                    .forEach { item ->
                        val flag = when (item.hint) {
                            AutofillHint.PASSWORD -> SaveInfo.SAVE_DATA_TYPE_PASSWORD
                            AutofillHint.EMAIL_ADDRESS -> SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS
                            AutofillHint.USERNAME -> SaveInfo.SAVE_DATA_TYPE_USERNAME
                            else -> return@forEach
                        }
                        saveItems += SaveItem(
                            flag = flag,
                            item = item,
                        )
                    }

                val hints = autofillStructure
                    .items
                    .asSequence()
                    .map { it.hint }
                    .toSet()
                val hintsIncludeUsername = AutofillHint.USERNAME in hints ||
                        AutofillHint.NEW_USERNAME in hints ||
                        AutofillHint.PHONE_NUMBER in hints ||
                        AutofillHint.EMAIL_ADDRESS in hints
                val hintsIncludePassword = AutofillHint.PASSWORD in hints ||
                        AutofillHint.NEW_PASSWORD in hints
                if (
                    hintsIncludeUsername &&
                    hintsIncludePassword &&
                    saveItems.isNotEmpty()
                ) {
                    val saveInfoBuilder = SaveInfo.Builder(
                        saveItems.fold(0) { y, x -> y or x.flag },
                        saveItems
                            .map { it.item.id }
                            .toTypedArray(),
                    )
                    val saveInfo = saveInfoBuilder.build()
                    responseBuilder.setSaveInfo(saveInfo)
                }
            }
            responseBuilder
                .build()
        }

    private fun AutofillStructure2.toAutofillTarget(
    ) = AutofillTarget(
        links = listOfNotNull(
            // application id
            applicationId?.let {
                LinkInfoPlatform.Android(
                    packageName = it,
                )
            },
            // website
            webDomain?.let {
                val schema = webScheme ?: "https"
                val url = Url("$schema://$it")
                LinkInfoPlatform.Web(
                    url = url,
                    frontPageUrl = url,
                )
            },
        ),
        hints = items.map { it.hint },
        maxCount = SUGGESTIONS_MAX_COUNT,
    )

    private enum class AuthenticationType {
        UNLOCK,
        SELECT,
    }

    private suspend fun FillResponse.Builder.buildAuthentication(
        type: AuthenticationType,
        struct: AutofillStructure2,
        request: FillRequest,
        canInlineSuggestions: Boolean,
    ) {
        val remoteViews: RemoteViews = when (type) {
            AuthenticationType.UNLOCK -> AutofillViews.buildPopupKeyguardUnlock(
                this@KeyguardAutofillService,
                struct.webDomain,
                struct.applicationId,
            )

            AuthenticationType.SELECT -> AutofillViews.buildPopupKeyguardOpen(
                this@KeyguardAutofillService,
                struct.webDomain,
                struct.applicationId,
            )
        }

        val authIntent = AutofillActivity.getIntent(
            context = this@KeyguardAutofillService,
            args = AutofillActivity.Args(
                applicationId = struct.applicationId,
                webDomain = struct.webDomain,
                webScheme = struct.webScheme,
                autofillStructure2 = struct,
            ),
        )
        val authIntentRequestCode = PendingIntents.autofill.obtainId()
        val authIntentSender = PendingIntent.getActivity(
            this@KeyguardAutofillService,
            authIntentRequestCode,
            authIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ).intentSender

        struct.items.forEach { item ->
            val builder = DatasetBuilder.create(
                menuPresentation = remoteViews,
                fields = mapOf(
                    item.id to null,
                ),
                provideInlinePresentation = {
                    if (!canInlineSuggestions) {
                        return@create null
                    }

                    tryCreateAuthenticationInlinePresentation(
                        type,
                        request,
                        0,
                        intent = authIntent,
                    )
                },
            )
            builder.setAuthentication(authIntentSender)
            addDataset(builder.build())
        }
    }

    private suspend fun tryBuildDataset(
        context: Context,
        secret: DSecret,
        struct: AutofillStructure2,
        provideInlinePresentation: () -> InlinePresentation?,
    ): Dataset? {
        val title = secret.name
        val text = kotlin.run {
            secret.login?.username?.also { return@run it }
            secret.card?.number?.also { return@run it }
            secret.uris.firstOrNull()
                ?.uri
                ?.also { return@run it }
            null
        }
        val views = AutofillViews.buildPopupEntry(
            context = this,
            title = title,
            text = text,
        )
        val fields = DatasetBuilder.fieldsStructData(
            cipher = secret,
            structItems = struct.items,
            getTotpCode = getTotpCode,
        )

        fun createDatasetBuilder(): Dataset.Builder {
            val builder = DatasetBuilder.create(
                menuPresentation = views,
                fields = DatasetBuilder.fields(
                    structItems = struct.items,
                    structData = fields,
                ),
                provideInlinePresentation = provideInlinePresentation,
            )
            builder.setId(secret.id)
            return builder
        }

        val builder = createDatasetBuilder()
        try {
            val dataset = createDatasetBuilder()
                .build()
            val intent = AutofillFakeAuthActivity.getIntent(
                this,
                dataset = dataset,
                cipher = secret,
                forceAddUri = false,
                structure = struct,
            )
            val code = PendingIntents.autofill.obtainId()
            val pi = PendingIntent.getActivity(
                this,
                code,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )

            builder.setAuthentication(pi.intentSender)
        } catch (e: Exception) {
            // Ignored
        }

        return try {
            builder.build()
        } catch (e: Exception) {
            null // not a single value set
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("RestrictedApi")
    private fun tryBuildSecretInlinePresentation(
        request: FillRequest,
        index: Int,
        secret: DSecret,
    ) = tryCreateInlinePresentation(
        request = request,
        index = index,
        createPendingIntent = {
            val intent = MainActivity.getIntent(
                context = this,
            )

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val code = PendingIntents.autofill.obtainId()
            PendingIntent.getActivity(this, code, intent, flags)
        },
        content = {
            setContentDescription(secret.name)
            setTitle(secret.name)
            val username = kotlin.run {
                secret.login?.username?.also { return@run it }
                secret.card?.number
                    // Take the last group of the
                    // card, otherwise it does not fit in.
                    ?.let { cardNumber ->
                        val cardNumberGroups = cardNumber
                            .windowed(
                                size = FormatCardGroupLength,
                                step = FormatCardGroupLength,
                                partialWindows = true,
                            )
                        if (cardNumberGroups.size < 4) {
                            return@let cardNumber
                        }

                        val cardNumberSuffix = cardNumberGroups.drop(3)
                            .joinToString(separator = " ")
                        "*$cardNumberSuffix"
                    }
                    ?.also { return@run it }
                secret.uris.firstOrNull()
                    ?.uri
                    ?.also { return@run it }
                null
            }
            username?.also(::setSubtitle)
        },
    )

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("RestrictedApi")
    private suspend fun tryBuildManualSelectionInlinePresentation(
        request: FillRequest,
        index: Int,
        intent: Intent,
    ) = tryCreateInlinePresentation(
        request = request,
        index = index,
        createPendingIntent = {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val code = PendingIntents.autofill.obtainId()
            PendingIntent.getActivity(this, code, intent, flags)
        },
        content = {
            val title = getString(Res.string.autofill_open_keyguard)
            setContentDescription(title)
            setTitle(title)
            setStartIcon(createAppIcon())
        },
    )

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun tryCreateAuthenticationInlinePresentation(
        type: AuthenticationType,
        request: FillRequest,
        index: Int,
        intent: Intent,
    ) = tryCreateInlinePresentation(
        request = request,
        index = index,
        createPendingIntent = {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val code = PendingIntents.autofill.obtainId()
            PendingIntent.getActivity(this, code, intent, flags)
        },
        content = {
            val text = when (type) {
                AuthenticationType.UNLOCK -> getString(Res.string.autofill_unlock_keyguard)
                AuthenticationType.SELECT -> getString(Res.string.autofill_open_keyguard)
            }
            setContentDescription(text)
            setTitle(text)
            setStartIcon(createAppIcon())
        },
    )

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private inline fun tryCreateInlinePresentation(
        request: FillRequest,
        index: Int,
        createPendingIntent: () -> PendingIntent,
        content: Content.Builder.() -> Unit,
    ): InlinePresentation? {
        val spec = request.inlineSuggestionsRequest
            ?.inlinePresentationSpecs
            ?.getOrNull(index)
            ?: request.inlineSuggestionsRequest
                ?.inlinePresentationSpecs
                ?.getOrNull(0)
            ?: return null
        val imeStyle = spec.style
        if (UiVersions.getVersions(imeStyle).contains(UiVersions.INLINE_UI_VERSION_1)) {
            val pi = createPendingIntent()
            return InlinePresentation(
                InlineSuggestionUi
                    .newContentBuilder(pi)
                    .apply {
                        content(this)
                    }
                    .build().slice,
                spec,
                false,
            )
        }
        return null
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createAppIcon() = Icon
        .createWithResource(this@KeyguardAutofillService, R.mipmap.ic_launcher)
        .apply {
            setTintBlendMode(BlendMode.DST)
        }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        getSaveStructureIo(request)
            .flatMap { autofillStructure ->
                if (autofillStructure.items.isEmpty()) {
                    throw AbortAutofillException("Nothing to autofill.")
                }

                getSaveResponseIo(
                    request = request,
                    autofillStructure = autofillStructure,
                )
            }
            .effectTap { intent ->
                if (Build.VERSION.SDK_INT >= 28) {
                    val flags =
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                    val code = PendingIntents.autofill.obtainId()
                    val pi = PendingIntent.getActivity(this, code, intent, flags)
                    callback.onSuccess(pi.intentSender)
                } else {
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        callback.onFailure(e.message)
                        return@effectTap
                    }
                    callback.onSuccess()
                }
            }
            .handleError { e ->
                logRepository.postDebug(TAG) {
                    "Save request: aborted because '$e'"
                }

                val msg = e.message ?: "Something went wrong"
                callback.onFailure(msg)
            }
            .dispatchOn(Dispatchers.Main.immediate)
            .launchIn(scope)
    }

    private fun getSaveResponseIo(
        request: SaveRequest,
        autofillStructure: AutofillStructure2,
    ) = ioEffect {
        val hints = autofillStructure
            .items
            .asSequence()
            .map { it.hint }
            .toSet()
        val hintsIncludeUsername = AutofillHint.USERNAME in hints ||
                AutofillHint.NEW_USERNAME in hints ||
                AutofillHint.PHONE_NUMBER in hints ||
                AutofillHint.EMAIL_ADDRESS in hints
        val hintsIncludePassword = AutofillHint.PASSWORD in hints ||
                AutofillHint.NEW_PASSWORD in hints
        if (
            !hintsIncludeUsername ||
            !hintsIncludePassword
        ) {
            throw AbortAutofillException("Can only save login data.")
        }

        AutofillSaveActivity.getIntent(
            context = this@KeyguardAutofillService,
            args = AutofillSaveActivity.Args(
                applicationId = autofillStructure.applicationId,
                webDomain = autofillStructure.webDomain,
                webScheme = autofillStructure.webScheme,
                autofillStructure2 = autofillStructure,
            ),
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    private suspend fun getString(res: StringResource) =
        org.jetbrains.compose.resources.getString(res)
}
