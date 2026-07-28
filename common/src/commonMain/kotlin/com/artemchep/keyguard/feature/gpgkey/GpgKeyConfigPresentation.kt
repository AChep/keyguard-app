package com.artemchep.keyguard.feature.gpgkey

import com.artemchep.keyguard.common.model.GpgKeyConfig
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.generator_gpg_key_modern_text
import com.artemchep.keyguard.res.generator_key_rsa_text

val GpgKeyConfig.Type.shortDescription: TextHolder
    get() = when (this) {
        GpgKeyConfig.Type.MODERN -> TextHolder.Res(Res.string.generator_gpg_key_modern_text)
        GpgKeyConfig.Type.RSA -> TextHolder.Res(Res.string.generator_key_rsa_text)
    }
