package com.artemchep.keyguard

import com.artemchep.keyguard.android.AutofillActivity
import com.artemchep.keyguard.android.AutofillSaveActivity
import com.artemchep.keyguard.common.model.AutofillHint
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.model.LinkInfoPlatform
import com.artemchep.keyguard.feature.home.vault.add.AddRoute
import com.artemchep.keyguard.feature.home.vault.add.of
import io.ktor.http.Url

actual typealias AutofillActArgs = AutofillActivity.Args

actual typealias AutofillSaveActArgs = AutofillSaveActivity.Args

actual val AppMode.autofillTarget: AutofillTarget?
    get() = when (this) {
        is AppMode.Pick ->
            AutofillTarget(
                links = listOfNotNull(
                    // application id
                    args.applicationId?.let {
                        LinkInfoPlatform.Android(
                            packageName = it,
                        )
                    },
                    // website
                    args.webDomain?.let {
                        val url = Url("https://$it")
                        LinkInfoPlatform.Web(
                            url = url,
                            frontPageUrl = url,
                        )
                    },
                ),
                hints = args.autofillStructure2?.items?.map { it.hint }.orEmpty(),
            )

        is AppMode.Save ->
            AutofillTarget(
                links = listOfNotNull(
                    // application id
                    args.applicationId?.let {
                        LinkInfoPlatform.Android(
                            packageName = it,
                        )
                    },
                    // website
                    args.webDomain?.let {
                        val url = Url("https://$it")
                        LinkInfoPlatform.Web(
                            url = url,
                            frontPageUrl = url,
                        )
                    },
                ),
                hints = args.autofillStructure2?.items?.map { it.hint }.orEmpty(),
            )

        is AppMode.Main ->
            null

        is AppMode.PickPasskey ->
            null

        is AppMode.SavePasskey -> {
            val rpId = rpId
            if (rpId != null) {
                AutofillTarget(
                    links = listOfNotNull(
                        // origin
                        run {
                            val url = Url(rpId.removePrefix("https://").let { "https://$it" })
                            LinkInfoPlatform.Web(
                                url = url,
                                frontPageUrl = url,
                            )
                        },
                    ),
                    hints = listOf(
                        AutofillHint.EMAIL_ADDRESS,
                    ),
                )
            } else {
                null
            }
        }
    }

actual val AppMode.generatorTarget: GeneratorContext
    get() = when (this) {
        is AppMode.Pick -> {
            val host = args.webDomain
            GeneratorContext(
                host = host,
            )
        }

        is AppMode.Save -> {
            val host = args.webDomain
            GeneratorContext(
                host = host,
            )
        }

        is AppMode.Main -> {
            GeneratorContext(
                host = null,
            )
        }

        is AppMode.PickPasskey -> {
            GeneratorContext(
                host = null,
            )
        }

        is AppMode.SavePasskey -> {
            val host = rpId
            GeneratorContext(
                host = host,
            )
        }
    }

actual fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillActArgs,
): AddRoute.Args.Autofill = of(source)

actual fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillSaveActArgs,
): AddRoute.Args.Autofill = of(source)
