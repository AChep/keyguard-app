package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.gpg_key_expiry_subkey_weak_self_signature_algorithms
import com.artemchep.keyguard.res.gpg_key_expiry_subkey_weak_self_signature_generic
import com.artemchep.keyguard.res.gpg_key_status_weak_self_signature_algorithms_text
import com.artemchep.keyguard.res.gpg_key_status_weak_self_signature_generic_text
import org.jetbrains.compose.resources.StringResource

internal suspend fun TranslatorScope.translateGpgWeakSelfSignature(
    algorithms: List<String>,
): String = translateGpgWeakSelfSignature(
    algorithms = algorithms,
    generic = Res.string.gpg_key_status_weak_self_signature_generic_text,
    detailed = Res.string.gpg_key_status_weak_self_signature_algorithms_text,
)

internal suspend fun TranslatorScope.translateGpgSubKeyWeakSelfSignature(
    algorithms: List<String>,
): String = translateGpgWeakSelfSignature(
    algorithms = algorithms,
    generic = Res.string.gpg_key_expiry_subkey_weak_self_signature_generic,
    detailed = Res.string.gpg_key_expiry_subkey_weak_self_signature_algorithms,
)

private suspend fun TranslatorScope.translateGpgWeakSelfSignature(
    algorithms: List<String>,
    generic: StringResource,
    detailed: StringResource,
): String {
    val names = algorithms.filter { it.isNotBlank() }.distinct().joinToString()
    return if (names.isEmpty()) {
        translate(generic)
    } else {
        translate(detailed, names)
    }
}
