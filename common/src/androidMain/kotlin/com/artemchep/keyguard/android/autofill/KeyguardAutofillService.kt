package com.artemchep.keyguard.android.autofill

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.BlendMode
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
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
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.*
import com.artemchep.keyguard.common.model.*
import com.artemchep.keyguard.common.usecase.*
import com.artemchep.keyguard.feature.home.vault.component.FormatCardGroupLength
import io.ktor.http.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.kodein.di.DIAware
import org.kodein.di.android.closestDI
import org.kodein.di.direct
import org.kodein.di.instance
import kotlin.coroutines.CoroutineContext

class KeyguardAutofillService : AutofillService(), DIAware {
    companion object {
        private const val TAG = "AFService"

        const val KEEP_CIPHERS_IN_MEMORY_FOR = 10_000L
    }

    private val job = Job()

    private val scope = object : CoroutineScope {
        override val coroutineContext: CoroutineContext
            get() = Dispatchers.Main + job
    }

    override val di by closestDI { this }

    private val ciphersFlow by lazy {
        val model: GetVaultSession by di.instance()
        model()
            .distinctUntilChanged()
            .flatMapLatest { session ->
                when (session) {
                    is MasterSession.Key -> {
                        val getCiphers = session.di.direct.instance<GetCiphers>()
                        val getProfiles = session.di.direct.instance<GetProfiles>()
                        val ciphersRawFlow = filterHiddenProfiles(
                            getProfiles = getProfiles,
                            getCiphers = getCiphers,
                            filter = null,
                        )
                        ciphersRawFlow
                            .map { ciphers ->
                                ciphers
                                    .filter { !it.deleted }
                                    .right()
                            }
                    }

                    is MasterSession.Empty -> {
                        val m = Unit.left()
                        flowOf(m)
                    }
                }
            }
            .flowOn(Dispatchers.Default)
            .shareIn(scope, SharingStarted.WhileSubscribed(KEEP_CIPHERS_IN_MEMORY_FOR), replay = 1)
    }

    private val getTotpCode: GetTotpCode by lazy {
        di.direct.instance()
    }

    private val getSuggestions by lazy {
        val model: GetSuggestions<Any?> by di.instance()
        model
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

    private val prefSaveUriFlow by lazy {
        val model: GetAutofillSaveUri by di.instance()
        model()
    }

    private val autofillStructureParser = AutofillStructureParser()

    @SuppressLint("NewApi")
    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val autofillStructure = kotlin.runCatching {
            val structureLatest = request.fillContexts
                .map { it.structure }
                .lastOrNull()
            // If the structure is missing, then abort auto-filling
            // process.
                ?: throw IllegalStateException("No structures to fill.")

            val respectAutofillOff = prefRespectAutofillOffFlow.toIO().bindBlocking()
            autofillStructureParser.parse(
                structureLatest,
                respectAutofillOff,
            )
        }
//            .flatMap {
//                val targetApplicationId = it.applicationId
//                val keyguardApplicationId = packageName
//                if (targetApplicationId == keyguardApplicationId) {
//                    val response = FillResponse.Builder()
//                        .disableAutofill(1000L)
//                        .build()
//                    callback.onSuccess(response)
//                    return
//                } else {
//                    Result.success(it)
//                }
//            }
            .fold(
                onSuccess = ::identity,
                onFailure = {
                    callback.onFailure("Failed to parse structures: ${it.message}")
                    return
                },
            )
        if (autofillStructure.items.isEmpty()) {
            callback.onFailure("Nothing to autofill.")
            return
        }

        val job = ciphersFlow
            .onStart {
                Log.e("LOL", "on start v2")
            }
            .toIO()
            .effectMap(Dispatchers.Default) { state ->
                state.map { secrets ->
                    val autofillTarget = AutofillTarget(
                        links = listOfNotNull(
                            // application id
                            autofillStructure.applicationId?.let {
                                LinkInfoPlatform.Android(
                                    packageName = it,
                                )
                            },
                            // website
                            autofillStructure.webDomain?.let {
                                val url = Url("https://$it")
                                LinkInfoPlatform.Web(
                                    url = url,
                                    frontPageUrl = url,
                                )
                            },
                        ),
                        hints = autofillStructure.items.map { it.hint },
                    )
                    getSuggestions(
                        secrets,
                        Getter { it as DSecret },
                        autofillTarget,
                    ).bind()
                        .take(10) as List<DSecret>
                }
            }
            .biEffectTap(
                ifException = {
                    // If the request is canceled, then it does not expect any
                    // feedback.
                    if (!cancellationSignal.isCanceled) {
                        callback.onFailure("Failed to get the secrets from the database!")
                    }
                },
                ifSuccess = { r ->
                    val canInlineSuggestions = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                            prefInlineSuggestionsFlow.toIO()
                                .attempt().bind().exists { it }

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
                                callback.onFailure("Can not autofill own app password.")
                                return@biEffectTap
                            }
                            // Database is locked, create a generic
                            // sign in with option.
                            responseBuilder.buildAuthentication(
                                type = FooBar.UNLOCK,
                                result2 = autofillStructure,
                                request = request,
                                canInlineSuggestions = canInlineSuggestions,
                            )
                        }

                        is Either.Right -> if (r.value.isEmpty()) {
                            if (forceHideManualSelection) {
                                callback.onFailure("No match found.")
                                return@biEffectTap
                            }
                            // No match found, create a generic option.
                            responseBuilder.buildAuthentication(
                                type = FooBar.SELECT,
                                result2 = autofillStructure,
                                request = request,
                                canInlineSuggestions = canInlineSuggestions,
                            )
                        } else {
                            val manualSelection = prefManualSelectionFlow.toIO().bind() &&
                                    !forceHideManualSelection

                            val totalInlineSuggestionsMaxCount = if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                canInlineSuggestions
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
                                var f = false
                                val dataset =
                                    tryBuildDataset(index, this, secret, autofillStructure) {
                                        if (index < secretInlineSuggestionsMaxCount) {
                                            val inline =
                                                tryBuildSecretInlinePresentation(
                                                    request,
                                                    index,
                                                    secret,
                                                )
                                            if (inline != null) {
                                                setInlinePresentation(inline)
                                                f = true
                                            }
                                        }
                                    }
                                if (dataset != null && (!canInlineSuggestions || f)) {
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
                                val pi = PendingIntent.getActivity(this, 1010, intent, flags)

                                val manualSelectionView = AutofillViews
                                    .buildPopupEntryManual(this)

                                autofillStructure.items.forEach {
                                    val autofillId = it.id
                                    Log.e("SuggestionsTest", "autofill_id=$autofillId")
                                    val builder = Dataset.Builder(manualSelectionView)

                                    if (totalInlineSuggestionsMaxCount > 0 && canInlineSuggestions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                        val inlinePresentation =
                                            tryBuildManualSelectionInlinePresentation(
                                                request,
                                                index,
                                                intent = intent,
                                            )
                                        inlinePresentation?.let {
                                            builder.setInlinePresentation(it)
                                        }
                                        Log.e(
                                            "SuggestionsTest",
                                            "adding inline=$inlinePresentation",
                                        )
                                    }
                                    builder.setValue(autofillId, null)
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

                        val items = mutableListOf<SaveItem>()
                        autofillStructure.items
                            .distinctBy { it.hint }
                            .forEach { item ->
                                val flag = when (item.hint) {
                                    AutofillHint.PASSWORD -> SaveInfo.SAVE_DATA_TYPE_PASSWORD
                                    AutofillHint.EMAIL_ADDRESS -> SaveInfo.SAVE_DATA_TYPE_EMAIL_ADDRESS
                                    AutofillHint.USERNAME -> SaveInfo.SAVE_DATA_TYPE_USERNAME
                                    else -> return@forEach
                                }
                                items += SaveItem(
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
                            items.isNotEmpty()
                        ) {
                            val saveInfoBuilder = SaveInfo.Builder(
                                items.fold(0) { y, x -> y or x.flag },
                                items
                                    .map { it.item.id }
                                    .toTypedArray(),
                            )
                            val saveInfo = saveInfoBuilder.build()
                            responseBuilder.setSaveInfo(saveInfo)
                        }
                    }
                    try {
                        val response = responseBuilder
                            .build()
                        callback.onSuccess(response)
                    } catch (e: Exception) {
                        callback.onFailure("Failed to build response ${e.localizedMessage}")
                    }
                },
            )
            .dispatchOn(Dispatchers.Main)
            .launchIn(scope)
        cancellationSignal.setOnCancelListener {
            job.cancel()
        }
    }

    private enum class FooBar {
        UNLOCK,
        SELECT,
    }

    private fun FillResponse.Builder.buildAuthentication(
        type: FooBar,
        result2: AutofillStructure2,
        request: FillRequest,
        canInlineSuggestions: Boolean,
    ) {
        val remoteViewsUnlock: RemoteViews = when (type) {
            FooBar.UNLOCK -> AutofillViews.buildPopupKeyguardUnlock(
                this@KeyguardAutofillService,
                result2.webDomain,
                result2.applicationId,
            )

            FooBar.SELECT -> AutofillViews.buildPopupKeyguardOpen(
                this@KeyguardAutofillService,
                result2.webDomain,
                result2.applicationId,
            )
        }

        result2.items.forEach {
            val autofillId = it.id
            val authIntent = AutofillActivity.getIntent(
                context = this@KeyguardAutofillService,
                args = AutofillActivity.Args(
                    applicationId = result2.applicationId,
                    webDomain = result2.webDomain,
                    webScheme = result2.webScheme,
                    autofillStructure2 = result2,
                ),
            )
            val intentSender: IntentSender = PendingIntent.getActivity(
                this@KeyguardAutofillService,
                1001,
                authIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).intentSender

            val builder = Dataset.Builder(remoteViewsUnlock)
            if (canInlineSuggestions && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val inlinePresentation =
                    tryCreateAuthenticationInlinePresentation(
                        type,
                        request,
                        0,
                        intent = authIntent,
                    )
                inlinePresentation?.let {
                    builder.setInlinePresentation(it)
                }
                Log.e(
                    "SuggestionsTest",
                    "adding inline=$inlinePresentation",
                )
            }
            builder.setValue(autofillId, null)
            builder.setAuthentication(intentSender)
            addDataset(builder.build())
        }
    }

    private fun tryBuildDataset(
        index: Int,
        context: Context,
        secret: DSecret,
        struct: AutofillStructure2,
        onComplete: (Dataset.Builder.() -> Unit)? = null,
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

        fun createDatasetBuilder(): Dataset.Builder {
            val builder = Dataset.Builder(views)
            builder.setId(secret.id)
            val fields = runBlocking {
                val hints = struct.items
                    .asSequence()
                    .map { it.hint }
                    .toSet()
                secret.gett(
                    hints = hints,
                    getTotpCode = getTotpCode,
                ).bind()
            }
            struct.items.forEach { structItem ->
                val value = fields[structItem.hint]
                builder.trySetValue(
                    id = structItem.id,
                    value = value,
                )
            }
            return builder
        }

        val builder = createDatasetBuilder()
        try {
            val dataset = createDatasetBuilder().build()
            val intent = AutofillFakeAuthActivity.getIntent(
                this,
                dataset = dataset,
                cipher = secret,
                forceAddUri = false,
                structure = struct,
            )
            val pi = PendingIntent.getActivity(
                this,
                10031 + index,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
            )

            builder.setAuthentication(pi.intentSender)
        } catch (e: Exception) {
            // Ignored
        }

        onComplete?.invoke(builder)

        return try {
            builder.build()
        } catch (e: Exception) {
            null // not a single value set
        }
    }

    private fun Dataset.Builder.trySetValue(
        id: AutofillId?,
        value: String?,
    ) {
        if (id != null && value != null) {
            setValue(id, AutofillValue.forText(value))
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
            PendingIntent.getActivity(this, 1010, intent, flags)
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
    private fun tryBuildManualSelectionInlinePresentation(
        request: FillRequest,
        index: Int,
        intent: Intent,
    ) = tryCreateInlinePresentation(
        request = request,
        index = index,
        createPendingIntent = {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(this, 1010, intent, flags)
        },
        content = {
            val title = getString(R.string.autofill_open_keyguard)
            setContentDescription(title)
            setTitle(title)
            setStartIcon(createAppIcon())
        },
    )

    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun tryCreateAuthenticationInlinePresentation(
        type: FooBar,
        request: FillRequest,
        index: Int,
        intent: Intent,
    ) = tryCreateInlinePresentation(
        request = request,
        index = index,
        createPendingIntent = {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(this, 1002, intent, flags)
        },
        content = {
            val text = when (type) {
                FooBar.UNLOCK -> getString(R.string.autofill_unlock_keyguard)
                FooBar.SELECT -> getString(R.string.autofill_open_keyguard)
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
            ?: return null
        val imeStyle = spec.style
        if (UiVersions.getVersions(imeStyle).contains(UiVersions.INLINE_UI_VERSION_1)) {
            val pi = createPendingIntent()
            return InlinePresentation(
                InlineSuggestionUi
                    .newContentBuilder(pi)
                    .apply(content)
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
        val autofillStructure = kotlin.runCatching {
            val structureLatest = request.fillContexts
                .map { it.structure }
                .lastOrNull()
            // If the structure is missing, then abort auto-filling
            // process.
                ?: throw IllegalStateException("No structures to fill.")

            val respectAutofillOff = prefRespectAutofillOffFlow.toIO().bindBlocking()
            autofillStructureParser.parse(
                structureLatest,
                respectAutofillOff,
            )
        }.fold(
            onSuccess = ::identity,
            onFailure = {
                callback.onFailure("Failed to parse structures: ${it.message}")
                return
            },
        )
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
            callback.onFailure("Can only save login data.")
            return
        }

        val intent = AutofillSaveActivity.getIntent(
            context = this@KeyguardAutofillService,
            args = AutofillSaveActivity.Args(
                applicationId = autofillStructure.applicationId,
                webDomain = autofillStructure.webDomain,
                webScheme = autofillStructure.webScheme,
                autofillStructure2 = autofillStructure,
            ),
        )
        if (Build.VERSION.SDK_INT >= 28) {
            val flags =
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            val pi = PendingIntent.getActivity(this, 10120, intent, flags)
            callback.onSuccess(pi.intentSender)
        } else {
            try {
                startActivity(intent)
            } catch (e: Exception) {
                callback.onFailure(e.message)
                return
            }
            callback.onSuccess()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}
