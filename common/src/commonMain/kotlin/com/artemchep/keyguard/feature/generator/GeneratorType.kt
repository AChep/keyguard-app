package com.artemchep.keyguard.feature.generator

import com.artemchep.keyguard.common.model.DGeneratorEmailRelay
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*

const val GENERATOR_TYPE_GROUP_PASSWORD = "password"
const val GENERATOR_TYPE_GROUP_USERNAME = "username"
const val GENERATOR_TYPE_GROUP_KEY_PAIR = "key_pair"
const val GENERATOR_TYPE_GROUP_INTEGRATION = "integration"

sealed interface GeneratorType2 {
    val key: String
    val group: String
    val title: TextHolder
    val username: Boolean get() = false
    val password: Boolean get() = false
    val sshKey: Boolean get() = false

    data object Password : GeneratorType2 {
        override val key: String = "PASSWORD"
        override val group: String = GENERATOR_TYPE_GROUP_PASSWORD
        override val title: TextHolder = TextHolder.Res(Res.string.generator_password_type)
        override val username: Boolean = true
        override val password: Boolean = true
    }

    data object Passphrase : GeneratorType2 {
        override val key: String = "PASSPHRASE"
        override val group: String = GENERATOR_TYPE_GROUP_PASSWORD
        override val title: TextHolder = TextHolder.Res(Res.string.generator_passphrase_type)
        override val password: Boolean = true
    }

    data object Username : GeneratorType2 {
        override val key: String = "USERNAME"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder = TextHolder.Res(Res.string.generator_username_type)
        override val username: Boolean = true
    }

    data object EmailCatchAll : GeneratorType2 {
        override val key: String = "EMAIL_CATCH_ALL"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder = TextHolder.Res(Res.string.generator_email_catch_all_type)
        override val username: Boolean = true
    }

    data object EmailPlusAddressing : GeneratorType2 {
        override val key: String = "EMAIL_PLUS_ADDRESSING"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder =
            TextHolder.Res(Res.string.generator_email_plus_addressing_type)
        override val username: Boolean = true
    }

    data object EmailSubdomainAddressing : GeneratorType2 {
        override val key: String = "EMAIL_SUBDOMAIN_ADDRESSING"
        override val group: String = GENERATOR_TYPE_GROUP_USERNAME
        override val title: TextHolder =
            TextHolder.Res(Res.string.generator_email_subdomain_addressing_type)
        override val username: Boolean = true
    }

    data class EmailRelay(
        val emailRelay: com.artemchep.keyguard.common.service.relays.api.EmailRelay,
        val config: DGeneratorEmailRelay,
    ) : GeneratorType2 {
        override val key: String = "EMAIL_RELAY:${emailRelay.type}:${config.id}"
        override val group: String = GENERATOR_TYPE_GROUP_INTEGRATION
        override val title: TextHolder = TextHolder.Value(config.name)
        override val username: Boolean = true
    }

    data object SshKey : GeneratorType2 {
        override val key: String = "SSH_KEY"
        override val group: String = GENERATOR_TYPE_GROUP_KEY_PAIR
        override val title: TextHolder =
            TextHolder.Res(Res.string.key_ssh)
        override val sshKey: Boolean = true
    }
}
