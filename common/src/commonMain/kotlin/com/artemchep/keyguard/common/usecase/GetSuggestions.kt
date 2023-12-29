package com.artemchep.keyguard.common.usecase

import arrow.optics.Getter
import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.AutofillTarget
import com.artemchep.keyguard.common.model.DSecret

interface GetSuggestions<T> : (
    List<T>,
    Getter<T, DSecret>,
    AutofillTarget,
) -> IO<List<T>>
