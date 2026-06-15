package com.artemchep.keyguard.android.autofill.v2.analyzer

import android.view.autofill.AutofillId
import com.artemchep.keyguard.android.autofill.v2.model.AnalysisContext
import com.artemchep.keyguard.android.autofill.v2.model.FieldCluster
import com.artemchep.keyguard.android.autofill.v2.model.FieldProposal
import com.artemchep.keyguard.android.autofill.v2.model.FormProposal

/**
 * Proposes the intent of a clustered set of fields and actions.
 */
interface FormAnalyzer {
    val id: String

    fun analyze(
        cluster: FieldCluster,
        context: AnalysisContext,
        fieldProposals: Map<AutofillId, List<FieldProposal>>,
    ): List<FormProposal>
}
