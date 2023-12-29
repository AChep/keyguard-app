package com.artemchep.keyguard.feature.send.search.filter

import arrow.optics.Getter
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class TypeSendFilter(
    override val id: String,
) : SendFilter, PureSendFilter by PureSendFilter(id, Getter { it.type })
