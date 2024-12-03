package com.artemchep.keyguard.common.usecase

import arrow.optics.Getter
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.EquivalentDomainsBuilderFactory

interface GetSuggestions<T> : (
    List<T>,
    Getter<T, DSecret>,
    AutofillTarget,
    EquivalentDomainsBuilderFactory,
) -> IO<List<T>>
