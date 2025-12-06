package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockRepositoryExposed
import com.artemchep.keyguard.common.usecase.GetAutofillBlockedUrisExposed
import org.kodein.di.DirectDI
import org.kodein.di.instance

class GetAutofillBlockedUrisExposedImpl(
    private val urlBlockRepositoryExposed: UrlBlockRepositoryExposed,
) : GetAutofillBlockedUrisExposed {
    constructor(directDI: DirectDI) : this(
        urlBlockRepositoryExposed = directDI.instance(),
    )

    override fun invoke() = urlBlockRepositoryExposed
        .get()
}
