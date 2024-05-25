package com.artemchep.keyguard.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.autofill.Dataset
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.android.autofill.AutofillStructure2
import com.artemchep.keyguard.common.R
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.gett
import com.artemchep.keyguard.common.usecase.GetTotpCode
import com.artemchep.keyguard.pick
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.theme.Dimens
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import org.kodein.di.DIAware
import org.kodein.di.direct
import org.kodein.di.instance

class AutofillActivity : BaseActivity(), DIAware {
    companion object {
        private const val KEY_ARGUMENTS = "arguments"

        fun getIntent(
            context: Context,
            args: Args,
        ): Intent = Intent(context, AutofillActivity::class.java).apply {
            putExtra(KEY_ARGUMENTS, args)
        }
    }

    @Parcelize
    data class Args(
        val autofillStructure2: AutofillStructure2? = null,
        val applicationId: String? = null,
        val webDomain: String? = null,
        val webScheme: String? = null,
    ) : Parcelable

    private val getTotpCode: GetTotpCode by lazy {
        di.direct.instance()
    }

    private val args by lazy {
        intent.extras?.getParcelable<Args>(KEY_ARGUMENTS)
            ?: Args(
                applicationId = packageName,
            )
    }

    private fun tryBuildDataset(
        index: Int,
        context: Context,
        secret: DSecret,
        forceAddUri: Boolean,
        struct: AutofillStructure2,
        onComplete: (Dataset.Builder.() -> Unit)? = null,
    ): Dataset? {
        val views = RemoteViews(context.packageName, R.layout.item_autofill_entry).apply {
            setTextViewText(R.id.autofill_entry_name, secret.name)
            val username = kotlin.run {
                secret.login?.username?.also { return@run it }
                secret.card?.number?.also { return@run it }
                secret.uris.firstOrNull()
                    ?.uri
                    ?.also { return@run it }
                null
            }
            if (username != null) {
                setTextViewText(R.id.autofill_entry_username, username)
            } else {
                setViewVisibility(R.id.autofill_entry_username, View.GONE)
            }
        }

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
                forceAddUri = forceAddUri,
                structure = struct,
            )
            val pi = PendingIntent.getActivity(
                this,
                1500 + index,
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

    private fun autofill(
        secret: DSecret,
        forceAddUri: Boolean,
    ) {
        val struct = args.autofillStructure2
            ?: return
        val dataset = tryBuildDataset(
            index = 555,
            context = this,
            secret = secret,
            forceAddUri = forceAddUri,
            struct = struct,
        )
        if (dataset != null) {
            val intent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
            setResult(RESULT_OK, intent)
            finish()
        } else {
            // TODO: Show a message or something
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened autofill pick activity")
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalAppMode provides AppMode.Pick(
                type = kotlin.run {
                    val loginSearch = args.autofillStructure2?.items?.any {
                        it.hint == AutofillHint.PASSWORD ||
                                it.hint == AutofillHint.PHONE_NUMBER ||
                                it.hint == AutofillHint.EMAIL_ADDRESS ||
                                it.hint == AutofillHint.USERNAME
                    } == true
                    val cardSearch = args.autofillStructure2?.items?.any {
                        it.hint == AutofillHint.CREDIT_CARD_NUMBER ||
                                it.hint == AutofillHint.CREDIT_CARD_SECURITY_CODE ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_DATE ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_DAY ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_YEAR ||
                                it.hint == AutofillHint.CREDIT_CARD_EXPIRATION_MONTH
                    } == true
                    if (loginSearch && cardSearch) {
                        null
                    } else if (loginSearch) {
                        DSecret.Type.Login
                    } else if (cardSearch) {
                        DSecret.Type.Card
                    } else {
                        null
                    }
                },
                args = args,
                onAutofill = { cipher, extra ->
                    autofill(
                        secret = cipher,
                        forceAddUri = extra.forceAddUri,
                    )
                },
            ),
        ) {
            AutofillScaffold(
                topBar = {
                    Column {
                        Row(
                            modifier = Modifier
                                .padding(
                                    vertical = 8.dp,
                                    horizontal = Dimens.horizontalPadding,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f),
                            ) {
                                val args = AppMode.pick.getOrNull(LocalAppMode.current)?.args
                                AppInfo(
                                    packageName = args?.applicationId,
                                    webDomain = args?.webDomain,
                                    webScheme = args?.webScheme,
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            val context by rememberUpdatedState(newValue = LocalContext.current)
                            TextButton(
                                onClick = {
                                    context.closestActivityOrNull?.finish()
                                },
                            ) {
                                Icon(Icons.Outlined.Close, null)
                                Spacer(
                                    modifier = Modifier
                                        .width(Dimens.buttonIconPadding),
                                )
                                Text(
                                    text = stringResource(Res.string.cancel),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}
