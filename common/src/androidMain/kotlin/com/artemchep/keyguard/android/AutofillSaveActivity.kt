package com.artemchep.keyguard.android

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.AppMode
import com.artemchep.keyguard.LocalAppMode
import com.artemchep.keyguard.android.autofill.AutofillStructure2
import com.artemchep.keyguard.android.util.getParcelableCompat
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.of
import com.artemchep.keyguard.platform.recordLog
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.colorizePassword
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import org.jetbrains.compose.resources.stringResource
import kotlinx.parcelize.Parcelize
import org.kodein.di.DIAware

class AutofillSaveActivity : BaseActivity(), DIAware {
    companion object {
        private const val KEY_ARGUMENTS = "arguments"

        fun getIntent(
            context: Context,
            args: Args,
        ): Intent = Intent(context, AutofillSaveActivity::class.java).apply {
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

    private val args by lazy {
        intent.extras?.getParcelableCompat<Args>(KEY_ARGUMENTS)
            ?: Args(
                applicationId = packageName,
            )
    }

    private val af by lazy {
        AddRoute.Args.Autofill.of(args)
    }

    private fun autofill(secret: DSecret) {
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordLog("Opened autofill save activity")
    }

    @Composable
    override fun Content() {
        CompositionLocalProvider(
            LocalAppMode provides AppMode.Save(
                type = DSecret.Type.Login,
                args = args,
                onAutofill = ::autofill,
            ),
        ) {
            args.autofillStructure2?.items?.forEach {
                it.hint
            }
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
                            Text(
                                modifier = Modifier
                                    .weight(1f),
                                text = "Save form data",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            val context by rememberUpdatedState(newValue = LocalContext.current)
                            TextButton(
                                modifier = Modifier
                                    .padding(start = Dimens.horizontalPadding),
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
                                    text = stringResource(Res.string.close),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .padding(horizontal = Dimens.horizontalPadding),
                        ) {
                            // Login
                            val username = af.username
                            if (username != null) {
                                key("username") {
                                    TwoColumnRow(
                                        title = stringResource(Res.string.username),
                                        value = username,
                                    )
                                }
                            }
                            val password = af.password
                            if (password != null) {
                                key("password") {
                                    val colorizedPassword = colorizePassword(
                                        password = password,
                                        contentColor = LocalContentColor.current,
                                    )
                                    TwoColumnRow(
                                        title = stringResource(Res.string.password),
                                        value = colorizedPassword,
                                    )
                                }
                            }

                            // Identity
                            val email = af.email
                            if (email != null) {
                                key("email") {
                                    TwoColumnRow(
                                        title = stringResource(Res.string.email),
                                        value = email,
                                    )
                                }
                            }
                            val phone = af.phone
                            if (phone != null) {
                                key("phone") {
                                    TwoColumnRow(
                                        title = stringResource(Res.string.phone_number),
                                        value = phone,
                                    )
                                }
                            }
                            val personName = af.personName
                            if (personName != null) {
                                key("personName") {
                                    TwoColumnRow(
                                        title = "Person name",
                                        value = personName,
                                    )
                                }
                            }

                            // Url
                            val applicationId = af.applicationId
                            val webDomain = af.webDomain
                            if (
                                applicationId != null ||
                                webDomain != null
                            ) {
                                Spacer(
                                    modifier = Modifier
                                        .height(8.dp),
                                )
                                val textStyle = MaterialTheme.typography.bodySmall
                                    .copy(
                                        color = LocalContentColor.current
                                            .combineAlpha(MediumEmphasisAlpha),
                                    )
                                if (applicationId != null) {
                                    key("app") {
                                        ProvideTextStyle(
                                            value = textStyle,
                                        ) {
                                            TwoColumnRow(
                                                title = "App",
                                                value = applicationId,
                                            )
                                        }
                                    }
                                }
                                if (webDomain != null) {
                                    key("webDomain") {
                                        ProvideTextStyle(
                                            value = textStyle,
                                        ) {
                                            TwoColumnRow(
                                                title = "Website",
                                                value = webDomain,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(
                            modifier = Modifier
                                .height(8.dp),
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun TwoColumnRow(
    modifier: Modifier = Modifier,
    title: String,
    value: CharSequence,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            modifier = Modifier
                .weight(2f)
                .widthIn(max = 120.dp),
            text = title,
            fontWeight = FontWeight.Medium,
        )
        Spacer(
            modifier = Modifier
                .width(16.dp),
        )

        val valueModifier = Modifier
            .weight(3f)
            .widthIn(max = 120.dp)
        when (value) {
            is String -> {
                Text(
                    modifier = valueModifier,
                    text = value,
                )
            }

            is AnnotatedString -> {
                Text(
                    modifier = valueModifier,
                    text = value,
                )
            }

            else -> error("Unsupported value type!")
        }
    }
}
