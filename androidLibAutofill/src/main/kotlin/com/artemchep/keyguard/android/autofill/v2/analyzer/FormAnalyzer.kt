package com.artemchep.keyguard.android.autofill.v2.analyzer

import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal

/**
 * Proposes the intent of a clustered set of fields and actions.
 */
interface FormAnalyzer {
    val id: String

    fun analyze(
        cluster: FieldCluster,
        context: AnalysisContext,
    ): List<FormProposal>
}
