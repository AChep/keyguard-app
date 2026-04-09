package com.artemchep.keyguard.android.autofill.v2.extract

import android.app.assist.AssistStructure
import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions

/**
 * Stage 1 of the v2 parser pipeline: extraction.
 *
 * Walks the Android [AssistStructure] view tree and produces a
 * [NormalizedStructureV2] containing all discovered input fields,
 * buttons, web-domain info, and `<form>` boundaries.
 */
interface StructureExtractorV2 {
    fun extract(
        assistStructure: AssistStructure,
        options: ParseOptions,
    ): NormalizedStructureV2
}
