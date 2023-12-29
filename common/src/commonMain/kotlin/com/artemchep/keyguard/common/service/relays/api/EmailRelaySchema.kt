package com.artemchep.keyguard.common.service.relays.api

import com.artemchep.keyguard.feature.confirmation.ConfirmationRoute
import com.artemchep.keyguard.feature.localization.TextHolder

/**
 * Schema defines the data that a user must fill
 * for sending a request. All the UI is drawn automatically
 * basing on the schema.
 */
data class EmailRelaySchema(
    val title: TextHolder,
    val hint: TextHolder? = null,
    val type: ConfirmationRoute.Args.Item.StringItem.Type = ConfirmationRoute.Args.Item.StringItem.Type.Text,
    /**
     * `true` if the empty value is a valid
     * value, `false` otherwise.
     */
    val canBeEmpty: Boolean = true,
)
