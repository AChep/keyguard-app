package com.artemchep.keyguard

import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.feature.home.vault.add.AddRoute

actual class AutofillActArgs(
    val autofillTarget: AutofillTarget? = null,
)

actual class AutofillSaveActArgs

actual val AppMode.autofillTarget: AutofillTarget?
    get() = when (this) {
        is AppMode.Pick -> args.autofillTarget
        else -> null
    }

actual val AppMode.generatorTarget: GeneratorContext
    get() = GeneratorContext(
        host = null,
    )

actual fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillSaveActArgs,
): AddRoute.Args.Autofill = AddRoute.Args.Autofill()

actual fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillActArgs,
): AddRoute.Args.Autofill = AddRoute.Args.Autofill()
