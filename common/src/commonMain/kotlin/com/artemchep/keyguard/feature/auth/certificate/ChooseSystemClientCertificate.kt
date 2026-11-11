package com.artemchep.keyguard.feature.auth.certificate

import com.artemchep.keyguard.common.io.runCatchingNonFatal
import com.artemchep.keyguard.common.model.ToastMessage
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.provider.bitwarden.ServerEnv
import com.artemchep.keyguard.provider.bitwarden.transport.MutualTlsSystemStore
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.client_certificate_system_none
import com.artemchep.keyguard.res.client_certificate_system_none_text
import com.artemchep.keyguard.res.error_failed_unknown

/**
 * Asks the user for a certificate of the system's store, with the [current]
 * one preselected. Returns `null` after telling the user why there is none.
 */
suspend fun RememberStateFlowScope.chooseSystemClientCertificate(
    store: MutualTlsSystemStore,
    current: ServerEnv.MutualTls?,
): ServerEnv.MutualTls.SystemStore? {
    val chosen = runCatchingNonFatal {
        store.choose(context, current as? ServerEnv.MutualTls.SystemStore)
    }.getOrElse {
        message(ToastMessage(
            type = ToastMessage.Type.ERROR,
            title = translate(Res.string.error_failed_unknown),
        ))
        return null
    }
    // The system does not tell a dismissed chooser from an empty one.
    if (chosen == null) {
        message(ToastMessage(
            type = ToastMessage.Type.INFO,
            title = translate(Res.string.client_certificate_system_none),
            text = translate(Res.string.client_certificate_system_none_text),
        ))
    }
    return chosen
}
