package com.artemchep.keyguard.android.autofill.v2.analyzer

import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldNode
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal

/**
 * Proposes semantic field types from structure-only signals.
 */
interface FieldAnalyzer {
    val id: String

    fun analyze(
        field: FieldNode,
        context: AnalysisContext,
    ): List<FieldProposal>
}
