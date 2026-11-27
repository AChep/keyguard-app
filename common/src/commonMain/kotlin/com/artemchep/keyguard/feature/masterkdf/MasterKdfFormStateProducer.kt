package com.artemchep.keyguard.feature.masterkdf

import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.MasterKdf
import com.artemchep.keyguard.common.usecase.MeasureMasterKdfUseCase
import com.artemchep.keyguard.feature.auth.common.TextFieldHandle
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.auth.common.Validated
import com.artemchep.keyguard.feature.auth.common.textFieldHandle
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.TranslatorScope
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The translated test result of the [kdf] it was measured with. */
private class MasterKdfTestResult(
    val kdf: MasterKdf.Argon2id,
    val text: String,
)

/** A field of the form with its value; `null` if the text is not valid. */
private class MasterKdfFieldModel(
    val model: TextFieldModel,
    val value: Int?,
)

/**
 * Creates the key derivation form. The fields are persisted under
 * the given [prefix].
 *
 * @param isBusyFlow `true` while the screen derives a key itself, for
 * example to create the vault. A test does not start meanwhile.
 */
// One place wires the fields, the test and the state; splitting it would
// only pass the same handles and sinks around.
@Suppress("LongMethod")
fun RememberStateFlowScope.masterKdfFormHandle(
    prefix: String,
    initial: MasterKdf.Argon2id,
    measureMasterKdf: MeasureMasterKdfUseCase,
    isBusyFlow: StateFlow<Boolean>,
    platform: Platform = CurrentPlatform,
): MasterKdfFormHandle {
    val iterations = textFieldHandle(
        key = "$prefix.iterations",
        initial = initial.iterationsText(),
    )
    val memory = textFieldHandle(
        key = "$prefix.memory",
        initial = initial.memoryMibText(),
    )
    val parallelism = textFieldHandle(
        key = "$prefix.parallelism",
        initial = initial.parallelismText(),
    )
    val currentKdf = {
        masterKdfOrNull(
            iterations = iterations.sink.value.text,
            memoryMib = memory.sink.value.text,
            parallelism = parallelism.sink.value.text,
        )
    }
    val onReset = {
        val kdf = MasterKdf.Argon2id.DEFAULT
        iterations.setText(kdf.iterationsText())
        memory.setText(kdf.memoryMibText())
        parallelism.setText(kdf.parallelismText())
    }

    val isTestingSink = MutableStateFlow(false)
    val testResultSink = MutableStateFlow<MasterKdfTestResult?>(null)
    val onTest = masterKdfTestAction(
        currentKdf = currentKdf,
        measureMasterKdf = measureMasterKdf,
        isBusyFlow = isBusyFlow,
        isTestingSink = isTestingSink,
        testResultSink = testResultSink,
    )

    val testFlow = combine(
        testResultSink,
        isTestingSink,
        isBusyFlow,
    ) { result, isTesting, isBusy ->
        Triple(result, isTesting, isBusy)
    }
    val translator: TranslatorScope = this
    val stateFlow = flow {
        // The warning does not depend on the entered
        // values, so translate it once.
        val memoryWarning = if (platform is Platform.Mobile.Ios) {
            translator.translate(
                Res.string.masterkdf_memory_warning_ios,
                MASTER_KDF_IOS_AUTOFILL_MEMORY_MIB_MAX,
            )
        } else {
            null
        }
        val flow = combine(
            iterations.masterKdfFieldFlow(translator, MasterKdfFormField.ITERATIONS),
            memory.masterKdfFieldFlow(translator, MasterKdfFormField.MEMORY_MIB),
            parallelism.masterKdfFieldFlow(translator, MasterKdfFormField.PARALLELISM),
            testFlow,
        ) { i, m, p, (testResult, isTesting, isBusy) ->
            val kdf = masterKdfOrNull(
                iterations = i.value,
                memoryMib = m.value,
                parallelism = p.value,
            )
            val isDefault = kdf == MasterKdf.Argon2id.DEFAULT
            // Show the result only for the values it was measured with.
            val resultText = testResult
                ?.takeIf { it.kdf == kdf }
                ?.text
            MasterKdfFormState(
                iterations = i.model,
                memory = m.model,
                parallelism = p.model,
                onReset = onReset.takeUnless { isDefault },
                test = MasterKdfFormState.Test(
                    isRunning = isTesting,
                    resultText = resultText,
                    onTest = onTest.takeIf { kdf != null && !isTesting && !isBusy },
                ),
                warning = memoryWarning
                    ?.takeIf { hasMasterKdfMemoryWarning(platform, m.value) },
                kdf = kdf,
            )
        }
        emitAll(flow)
    }
    return MasterKdfFormHandle(
        stateFlow = stateFlow,
        isTestingFlow = isTestingSink.asStateFlow(),
        currentKdf = currentKdf,
    )
}

/**
 * Derives a key once with the entered values and remembers how long
 * it took. Only one derivation runs at a time.
 */
private fun RememberStateFlowScope.masterKdfTestAction(
    currentKdf: () -> MasterKdf.Argon2id?,
    measureMasterKdf: MeasureMasterKdfUseCase,
    isBusyFlow: StateFlow<Boolean>,
    isTestingSink: MutableStateFlow<Boolean>,
    testResultSink: MutableStateFlow<MasterKdfTestResult?>,
): () -> Unit = onTest@{
    val kdf = currentKdf()
        ?: return@onTest
    if (isBusyFlow.value) return@onTest
    if (!isTestingSink.compareAndSet(expect = false, update = true)) return@onTest

    val job = screenScope.launch {
        measureMasterKdf(kdf)
            .attempt()
            .bind()
            .fold(
                ifLeft = { e ->
                    message(e)
                },
                ifRight = { duration ->
                    val text = translate(
                        Res.string.masterkdf_test_result,
                        formatMasterKdfSeconds(duration),
                    )
                    testResultSink.value = MasterKdfTestResult(
                        kdf = kdf,
                        text = text,
                    )
                },
            )
    }
    // A job may be cancelled before its body runs, so release
    // the flag on completion instead.
    job.invokeOnCompletion {
        isTestingSink.value = false
    }
}

/** Validates the field once per edit, keeping the parsed value. */
private fun TextFieldHandle.masterKdfFieldFlow(
    translator: TranslatorScope,
    field: MasterKdfFormField,
): Flow<MasterKdfFieldModel> = sink.map { cell ->
    val validation = validateMasterKdfField(field, cell.text)
    val error = when (validation) {
        is MasterKdfFieldValidation.Valid -> null
        MasterKdfFieldValidation.Empty -> translator.translate(Res.string.error_must_not_be_blank)
        MasterKdfFieldValidation.NotANumber -> translator.translate(Res.string.error_must_be_number)
        MasterKdfFieldValidation.OutOfRange -> translator.translate(
            Res.string.masterkdf_error_out_of_range,
            field.min,
            field.max,
        )
    }
    val validated = if (error != null) {
        Validated.Failure(model = cell.text, error = error)
    } else {
        Validated.Success(model = cell.text)
    }
    MasterKdfFieldModel(
        model = TextFieldModel.of(
            cell = cell,
            handle = this,
            validated = validated,
        ),
        value = (validation as? MasterKdfFieldValidation.Valid)?.value,
    )
}
