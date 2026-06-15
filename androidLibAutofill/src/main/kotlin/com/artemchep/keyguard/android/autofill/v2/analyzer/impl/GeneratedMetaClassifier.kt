package com.artemchep.keyguard.android.autofill.v2.analyzer.impl

import kotlin.math.exp

/**
 * Auto-generated multinomial logistic regression meta-classifier.
 * Trained on 52 features, 5 classes.
 * DO NOT EDIT — regenerate via MetaClassifierTrainer.
 */
object GeneratedMetaClassifier {
    /** Class labels in prediction order. */
    val CLASS_NAMES = arrayOf("EMAIL_ADDRESS", "PASSWORD", "USERNAME", "PHONE_NUMBER", "NONE")

    const val NUM_FEATURES = 52
    const val NUM_CLASSES = 5

    private val BIASES = doubleArrayOf(
        -0.3863729005, // EMAIL_ADDRESS
        -2.3605292907, // PASSWORD
        -1.1700740816, // USERNAME
        -0.3746151698, // PHONE_NUMBER
        4.2915914426 // NONE
    )

    private val WEIGHTS = arrayOf(
        // EMAIL_ADDRESS
        doubleArrayOf(
            0.2710134799, // autocomplete_EMAIL_ADDRESS
            0.0937345093, // autocomplete_PASSWORD
            -0.2163111863, // autocomplete_USERNAME
            -0.1149244003, // autocomplete_PHONE_NUMBER
            0.0553367152, // autocomplete_OTP
            0.0693328746, // autocomplete_PERSON_NAME
            -0.0847711320, // autocomplete_SEARCH
            -0.1448751111, // autocomplete_COMMENT
            0.5968995895, // html-type_EMAIL_ADDRESS
            -0.3488963683, // html-type_PASSWORD
            0.0832240622, // html-type_USERNAME
            0.0652458788, // html-type_PHONE_NUMBER
            0.1463131144, // html-type_OTP
            -0.0664119383, // html-type_PERSON_NAME
            -0.1436274521, // html-type_SEARCH
            0.0366956130, // html-type_COMMENT
            1.0388497438, // text-signal_EMAIL_ADDRESS
            -0.0711675565, // text-signal_PASSWORD
            0.0152370427, // text-signal_USERNAME
            -0.1880026338, // text-signal_PHONE_NUMBER
            0.0884971758, // text-signal_OTP
            -0.1061454172, // text-signal_PERSON_NAME
            -0.3734446642, // text-signal_SEARCH
            0.0406885618, // text-signal_COMMENT
            1.5182829592, // tree_EMAIL_ADDRESS
            -0.1219378381, // tree_PASSWORD
            0.1918749043, // tree_USERNAME
            -0.0741667062, // tree_PHONE_NUMBER
            -0.0179755664, // tree_OTP
            -0.2173628272, // tree_PERSON_NAME
            0.0964424624, // tree_SEARCH
            -0.2310692056, // tree_COMMENT
            1.3235887504, // numAnalyzers_EMAIL_ADDRESS
            -0.8163576155, // numAnalyzers_PASSWORD
            0.0658126504, // numAnalyzers_USERNAME
            -0.3488837508, // numAnalyzers_PHONE_NUMBER
            -0.0618866940, // numAnalyzers_OTP
            -0.1158556805, // numAnalyzers_PERSON_NAME
            -0.5453330360, // numAnalyzers_SEARCH
            -0.1829501461, // numAnalyzers_COMMENT
            -0.1183120157, // formIntent_LOGIN
            0.0449269186, // formIntent_SIGN_UP
            -0.4933995087, // formIntent_AUTH_COMBINED
            -0.3221876630, // formIntent_PASSWORD_RESET
            -0.3519316339, // formIntent_SEARCH
            -0.0516007990, // formIntent_UNKNOWN
            -0.2932321111, // hasVisibleLabel
            0.2809773968, // hasStructuralSignal
            -0.8080817132, // hasNameIdSignal
            -0.1886704201, // clusterFieldCountNorm
            0.0660618056, // clusterHasPassword
            -0.6801536457 // clusterHasIdentifier
        ),
        // PASSWORD
        doubleArrayOf(
            0.0244821895, // autocomplete_EMAIL_ADDRESS
            0.2516165061, // autocomplete_PASSWORD
            0.1391360132, // autocomplete_USERNAME
            -0.1577830365, // autocomplete_PHONE_NUMBER
            0.0265968702, // autocomplete_OTP
            0.0681904384, // autocomplete_PERSON_NAME
            -0.0549597119, // autocomplete_SEARCH
            0.0559132702, // autocomplete_COMMENT
            0.0569161506, // html-type_EMAIL_ADDRESS
            1.4968396232, // html-type_PASSWORD
            -0.1953435780, // html-type_USERNAME
            -0.1903023647, // html-type_PHONE_NUMBER
            0.0337985714, // html-type_OTP
            -0.0934931302, // html-type_PERSON_NAME
            -0.0141817934, // html-type_SEARCH
            -0.0491642316, // html-type_COMMENT
            -0.1531235090, // text-signal_EMAIL_ADDRESS
            -0.4801266938, // text-signal_PASSWORD
            -0.1554717003, // text-signal_USERNAME
            -0.2097742855, // text-signal_PHONE_NUMBER
            0.1003773167, // text-signal_OTP
            0.1283799208, // text-signal_PERSON_NAME
            0.0604215386, // text-signal_SEARCH
            0.1570903660, // text-signal_COMMENT
            0.1216368093, // tree_EMAIL_ADDRESS
            2.7405941774, // tree_PASSWORD
            -0.0587228448, // tree_USERNAME
            -0.1386214911, // tree_PHONE_NUMBER
            0.0833496389, // tree_OTP
            -0.0403245819, // tree_PERSON_NAME
            -0.1144436572, // tree_SEARCH
            -0.2328961370, // tree_COMMENT
            -0.4539460015, // numAnalyzers_EMAIL_ADDRESS
            1.6206028783, // numAnalyzers_PASSWORD
            -0.6307782220, // numAnalyzers_USERNAME
            -0.3939198540, // numAnalyzers_PHONE_NUMBER
            -0.0109957495, // numAnalyzers_OTP
            0.2377041618, // numAnalyzers_PERSON_NAME
            -0.1147494088, // numAnalyzers_SEARCH
            -0.0046437811, // numAnalyzers_COMMENT
            -0.3996027261, // formIntent_LOGIN
            -0.3626771607, // formIntent_SIGN_UP
            -0.1217280880, // formIntent_AUTH_COMBINED
            0.6324814484, // formIntent_PASSWORD_RESET
            -0.1383032677, // formIntent_SEARCH
            0.1189475905, // formIntent_UNKNOWN
            -0.1558402380, // hasVisibleLabel
            0.9765835166, // hasStructuralSignal
            -0.4738714818, // hasNameIdSignal
            0.1845878706, // clusterFieldCountNorm
            0.4234265863, // clusterHasPassword
            0.2395672425 // clusterHasIdentifier
        ),
        // USERNAME
        doubleArrayOf(
            -0.2065199377, // autocomplete_EMAIL_ADDRESS
            0.1394345333, // autocomplete_PASSWORD
            0.3873302444, // autocomplete_USERNAME
            0.0771523615, // autocomplete_PHONE_NUMBER
            0.1596065532, // autocomplete_OTP
            0.1447985045, // autocomplete_PERSON_NAME
            -0.0199248005, // autocomplete_SEARCH
            -0.2338607114, // autocomplete_COMMENT
            -1.2444356826, // html-type_EMAIL_ADDRESS
            -0.5437873654, // html-type_PASSWORD
            -0.0772743688, // html-type_USERNAME
            0.1136943617, // html-type_PHONE_NUMBER
            0.1846147757, // html-type_OTP
            0.0069918950, // html-type_PERSON_NAME
            -0.0984901626, // html-type_SEARCH
            -0.0604415749, // html-type_COMMENT
            0.2058218409, // text-signal_EMAIL_ADDRESS
            0.2361993672, // text-signal_PASSWORD
            0.8528582390, // text-signal_USERNAME
            -0.2808457067, // text-signal_PHONE_NUMBER
            -0.0403482601, // text-signal_OTP
            -0.1793387988, // text-signal_PERSON_NAME
            -0.1585606288, // text-signal_SEARCH
            0.0069073445, // text-signal_COMMENT
            0.8254180787, // tree_EMAIL_ADDRESS
            -0.1561722272, // tree_PASSWORD
            1.2666208672, // tree_USERNAME
            -0.0927356855, // tree_PHONE_NUMBER
            0.0578861123, // tree_OTP
            -0.1639131150, // tree_PERSON_NAME
            0.4310276765, // tree_SEARCH
            -0.2193102467, // tree_COMMENT
            -0.2484104423, // numAnalyzers_EMAIL_ADDRESS
            -0.7118851656, // numAnalyzers_PASSWORD
            1.7183730807, // numAnalyzers_USERNAME
            -0.5095043350, // numAnalyzers_PHONE_NUMBER
            -0.1281848078, // numAnalyzers_OTP
            -0.3301650131, // numAnalyzers_PERSON_NAME
            0.3252609818, // numAnalyzers_SEARCH
            -0.2871270442, // numAnalyzers_COMMENT
            0.0565725251, // formIntent_LOGIN
            0.6558635350, // formIntent_SIGN_UP
            0.2194501469, // formIntent_AUTH_COMBINED
            0.0069658372, // formIntent_PASSWORD_RESET
            0.2617097601, // formIntent_SEARCH
            0.0090455361, // formIntent_UNKNOWN
            0.4017930992, // hasVisibleLabel
            -1.4821884591, // hasStructuralSignal
            1.5286752402, // hasNameIdSignal
            0.4281107915, // clusterFieldCountNorm
            -0.3807054382, // clusterHasPassword
            0.5011211718 // clusterHasIdentifier
        ),
        // PHONE_NUMBER
        doubleArrayOf(
            -0.1647206226, // autocomplete_EMAIL_ADDRESS
            0.1646067769, // autocomplete_PASSWORD
            0.0909991025, // autocomplete_USERNAME
            0.1717293934, // autocomplete_PHONE_NUMBER
            0.1119811103, // autocomplete_OTP
            0.1220077180, // autocomplete_PERSON_NAME
            0.0907771016, // autocomplete_SEARCH
            -0.0300824273, // autocomplete_COMMENT
            -0.5083492754, // html-type_EMAIL_ADDRESS
            -0.6207549600, // html-type_PASSWORD
            0.1849530830, // html-type_USERNAME
            0.8449817027, // html-type_PHONE_NUMBER
            0.0462567561, // html-type_OTP
            0.1443429083, // html-type_PERSON_NAME
            -0.3365390226, // html-type_SEARCH
            -0.0831740359, // html-type_COMMENT
            -0.4574585465, // text-signal_EMAIL_ADDRESS
            -0.0430577186, // text-signal_PASSWORD
            -0.5387220742, // text-signal_USERNAME
            1.1597423283, // text-signal_PHONE_NUMBER
            0.3088246408, // text-signal_OTP
            -0.0635873126, // text-signal_PERSON_NAME
            0.4777897462, // text-signal_SEARCH
            0.1634998429, // text-signal_COMMENT
            0.0403195441, // tree_EMAIL_ADDRESS
            -0.2988908964, // tree_PASSWORD
            0.1197481183, // tree_USERNAME
            0.4835417437, // tree_PHONE_NUMBER
            -0.0519134722, // tree_OTP
            -0.2221941908, // tree_PERSON_NAME
            -0.1554565250, // tree_SEARCH
            -0.2140539836, // tree_COMMENT
            -0.4424020171, // numAnalyzers_EMAIL_ADDRESS
            -0.7222223913, // numAnalyzers_PASSWORD
            -0.3572350103, // numAnalyzers_USERNAME
            2.0324682828, // numAnalyzers_PHONE_NUMBER
            0.1957272686, // numAnalyzers_OTP
            -0.0020196290, // numAnalyzers_PERSON_NAME
            0.0849134860, // numAnalyzers_SEARCH
            -0.1898903721, // numAnalyzers_COMMENT
            0.4687733055, // formIntent_LOGIN
            0.0140596411, // formIntent_SIGN_UP
            -0.1127762272, // formIntent_AUTH_COMBINED
            -0.3316965172, // formIntent_PASSWORD_RESET
            0.3948702524, // formIntent_SEARCH
            -0.2887118753, // formIntent_UNKNOWN
            -0.0419744334, // hasVisibleLabel
            0.5770127423, // hasStructuralSignal
            -0.0272053467, // hasNameIdSignal
            0.3424594100, // clusterFieldCountNorm
            -0.0614612165, // clusterHasPassword
            -0.2298778745 // clusterHasIdentifier
        ),
        // NONE
        doubleArrayOf(
            -0.1941841481, // autocomplete_EMAIL_ADDRESS
            -0.1505258658, // autocomplete_PASSWORD
            -0.4921661164, // autocomplete_USERNAME
            0.0814299546, // autocomplete_PHONE_NUMBER
            -0.0313036552, // autocomplete_OTP
            -0.0183001858, // autocomplete_PERSON_NAME
            0.0402893581, // autocomplete_SEARCH
            -0.0603845965, // autocomplete_COMMENT
            1.4441875797, // html-type_EMAIL_ADDRESS
            0.1951211782, // html-type_PASSWORD
            0.0066382915, // html-type_USERNAME
            -0.6179331509, // html-type_PHONE_NUMBER
            0.1224713261, // html-type_OTP
            0.0954136345, // html-type_PERSON_NAME
            0.7332589672, // html-type_SEARCH
            -0.0073530186, // html-type_COMMENT
            -0.7225241108, // text-signal_EMAIL_ADDRESS
            0.4290653159, // text-signal_PASSWORD
            -0.0815321142, // text-signal_USERNAME
            -0.3868361659, // text-signal_PHONE_NUMBER
            0.0685454814, // text-signal_OTP
            -0.0546103573, // text-signal_PERSON_NAME
            0.2168093118, // text-signal_SEARCH
            0.1512524183, // text-signal_COMMENT
            -2.1137707198, // tree_EMAIL_ADDRESS
            -2.2349186197, // tree_PASSWORD
            -1.1219635844, // tree_USERNAME
            -0.2594845611, // tree_PHONE_NUMBER
            0.0161536433, // tree_OTP
            0.0797404732, // tree_PERSON_NAME
            0.1170505923, // tree_SEARCH
            0.3854574238, // tree_COMMENT
            -0.4078671482, // numAnalyzers_EMAIL_ADDRESS
            0.5243334967, // numAnalyzers_PASSWORD
            -0.9278692313, // numAnalyzers_USERNAME
            -0.6843867026, // numAnalyzers_PHONE_NUMBER
            0.0762160067, // numAnalyzers_OTP
            0.4444901825, // numAnalyzers_PERSON_NAME
            0.2948371650, // numAnalyzers_SEARCH
            0.4740425857, // numAnalyzers_COMMENT
            -0.0555520522, // formIntent_LOGIN
            -0.5569385087, // formIntent_SIGN_UP
            0.4470616470, // formIntent_AUTH_COMBINED
            0.0903469187, // formIntent_PASSWORD_RESET
            0.0604267462, // formIntent_SEARCH
            0.3393611195, // formIntent_UNKNOWN
            -0.0066238088, // hasVisibleLabel
            -0.1827376621, // hasStructuralSignal
            -0.4938923159, // hasNameIdSignal
            -0.5919438270, // clusterFieldCountNorm
            0.1122759035, // clusterHasPassword
            -0.0095921451 // clusterHasIdentifier
        ),
    )

    /**
     * Predicts class probabilities for the given feature vector.
     * Returns a [DoubleArray] of length [NUM_CLASSES] that sums to 1.
     */
    fun predict(features: DoubleArray): DoubleArray {
        val logits = DoubleArray(NUM_CLASSES)
        for (c in 0 until NUM_CLASSES) {
            var dot = BIASES[c]
            val w = WEIGHTS[c]
            for (f in 0 until NUM_FEATURES) {
                dot += w[f] * features[f]
            }
            logits[c] = dot
        }
        return softmax(logits)
    }

    /**
     * Returns the index of the class with the highest probability.
     */
    fun predictClass(features: DoubleArray): Int {
        val probs = predict(features)
        return probs.indices.maxByOrNull { probs[it] } ?: 0
    }

    private fun softmax(logits: DoubleArray): DoubleArray {
        val maxLogit = logits.max()
        val exps = DoubleArray(logits.size) { exp(logits[it] - maxLogit) }
        val sumExp = exps.sum()
        return DoubleArray(exps.size) { exps[it] / sumExp }
    }
}
