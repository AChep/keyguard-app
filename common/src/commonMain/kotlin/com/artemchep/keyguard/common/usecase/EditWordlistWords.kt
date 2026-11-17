package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.io.IO
import com.artemchep.keyguard.common.model.EditWordlistWordsRequest

/**
 * Adds, removes or replaces the words of a wordlist. Fails with
 * [com.artemchep.keyguard.common.exception.WordlistWordException]
 * if the change is refused.
 */
interface EditWordlistWords : (EditWordlistWordsRequest) -> IO<Unit>
