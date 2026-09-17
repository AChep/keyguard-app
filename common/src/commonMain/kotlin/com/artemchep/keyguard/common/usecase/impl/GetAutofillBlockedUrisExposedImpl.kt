package com.artemchep.keyguard.common.usecase.impl

import com.artemchep.keyguard.common.service.urlblock.impl.UrlBlockRepositoryExposed
import com.artemchep.keyguard.common.usecase.GetAutofillBlockedUrisExposed

class GetAutofillBlockedUrisExposedImpl(
    private val urlBlockRepositoryExposed: UrlBlockRepositoryExposed,
) : GetAutofillBlockedUrisExposed {
    override fun invoke() = urlBlockRepositoryExposed
        .get()
}
