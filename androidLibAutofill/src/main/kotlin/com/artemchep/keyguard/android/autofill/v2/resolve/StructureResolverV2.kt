package com.artemchep.keyguard.android.autofill.v2.resolve

import com.artemchep.keyguard.android.autofill.v2.model.NormalizedStructureV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseDebugResultV2
import com.artemchep.keyguard.android.autofill.v2.model.ParseOptions

/**
 * Stage 3 of the v2 parser pipeline: resolution.
 *
 * Takes the clustered [NormalizedStructureV2] and resolves each field's
 * [SemanticType][com.artemchep.keyguard.android.autofill.v2.model.SemanticType]
 * and each cluster's [FormIntent][com.artemchep.keyguard.android.autofill.v2.model.FormIntent]
 * by combining proposals from multiple analyzers.
 */
interface StructureResolverV2 {
    fun resolve(
        clustered: NormalizedStructureV2,
        options: ParseOptions = ParseOptions(),
    ): ParseDebugResultV2
}
