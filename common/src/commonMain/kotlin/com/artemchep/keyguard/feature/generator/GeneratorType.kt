package com.artemchep.keyguard.feature.generator

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res

const val GENERATOR_TYPE_GROUP_PASSWORD = "password"
const val GENERATOR_TYPE_GROUP_USERNAME = "username"
const val GENERATOR_TYPE_GROUP_INTEGRATION = "integration"

sealed interface GeneratorType2 {
    val key: String
    val group: String
    val title: TextHolder
    val username: Boolean
    val password: Boolean

    data object Password : GeneratorType2 {
        override val key: String = "PASSWORD"
        override val group: String = GENERATOR_TYPE_GROUP_PASSWORD
        override val title: TextHolder = TextHolder.Res(Res.strings.generator_password_type)
        override val username: Boolean = true
        override val password: Boolean = true
    }

    data object Passphrase : GeneratorType2 {
        override val key: String = "PASSPHRASE"
        override val group: String = GENERATOR_TYPE_GROUP_PASSWORD
        override val title: TextHolder = TextHolder.Res(Res.strings.generator_passphrase_type)
        override val username: Boolean = false
        override val password: Boolean = true
    }

    data object Username : GeneratorType2 {
        override val key: String = "USERNAME"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder = TextHolder.Res(Res.strings.generator_username_type)
        override val username: Boolean = true
        override val password: Boolean = false
    }

    data object EmailCatchAll : GeneratorType2 {
        override val key: String = "EMAIL_CATCH_ALL"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder = TextHolder.Res(Res.strings.generator_email_catch_all_type)
        override val username: Boolean = true
        override val password: Boolean = false
    }

    data object EmailPlusAddressing : GeneratorType2 {
        override val key: String = "EMAIL_PLUS_ADDRESSING"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder =
            TextHolder.Res(Res.strings.generator_email_plus_addressing_type)
        override val username: Boolean = true
        override val password: Boolean = false
    }

    data object EmailSubdomainAddressing : GeneratorType2 {
        override val key: String = "EMAIL_SUBDOMAIN_ADDRESSING"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder =
            TextHolder.Res(Res.strings.generator_email_subdomain_addressing_type)
        override val username: Boolean = true
        override val password: Boolean = false
    }

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : GeneratorType2 {
        override val key: String = "EMAIL_RELAY:${emailRelay.type}"
        override val group: String = GENERATOR_TYPE_GROUP_INTEGRATION
        override val title: TextHolder = TextHolder.Value(config.name)
        override val username: Boolean = true
        override val password: Boolean = false
    }
}
