package com.artemchep.keyguard.feature.home.vault.search.filter

import arrow.optics.Getter
import com.artemchep.keyguard.platform.parcelize.LeParcelize

@LeParcelize
data class TypeFilter(
    override val id: String,
) : Filter, PureFilter by PureFilter(id, Getter { it.type })
