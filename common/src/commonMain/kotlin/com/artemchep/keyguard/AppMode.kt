package com.artemchep.keyguard

import arrow.optics.optics
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.GeneratorContext
import com.artemchep.keyguard.common.usecase.PasskeyTarget
import com.artemchep.keyguard.feature.home.vault.add.AddRoute

@optics
sealed interface AppMode {
    companion object;

    interface HasType {
        val type: DSecret.Type?
    }

    data object Main : AppMode

    @optics
    data class Pick(
        override val type: DSecret.Type? = null,
        val args: AutofillActArgs,
        val onAutofill: (DSecret) -> Unit,
    ) : AppMode, HasType {
        companion object
    }

    @optics
    data class Save(
        override val type: DSecret.Type? = null,
        val args: AutofillSaveActArgs,
        val onAutofill: (DSecret) -> Unit,
    ) : AppMode, HasType {
        companion object
    }

    @optics
    data class PickPasskey(
        val target: PasskeyTarget,
        val onComplete: (DSecret.Login.Fido2Credentials) -> Unit,
    ) : AppMode, HasType {
        companion object;

        // When in the passkeys mode only allow
        // a user to see and create logins.
        override val type: DSecret.Type get() = DSecret.Type.Login
    }

    @optics
    data class SavePasskey(
        val rpId: String?,
        val onComplete: (DSecret) -> Unit,
    ) : AppMode, HasType {
        companion object;

        // When in the passkeys mode only allow
        // a user to see and create logins.
        override val type: DSecret.Type get() = DSecret.Type.Login
    }
}

expect fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillActArgs,
): AddRoute.Args.Autofill

expect fun AddRoute.Args.Autofill.Companion.leof(
    source: AutofillSaveActArgs,
): AddRoute.Args.Autofill

expect val AppMode.autofillTarget: AutofillTarget?

expect val AppMode.generatorTarget: GeneratorContext

expect class AutofillActArgs

expect class AutofillSaveActArgs
