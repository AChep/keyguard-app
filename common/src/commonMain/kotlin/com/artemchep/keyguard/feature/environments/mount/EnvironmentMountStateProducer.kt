package com.artemchep.keyguard.feature.environments.mount

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.environment.EnvironmentAccess
import com.artemchep.keyguard.common.service.environment.EnvironmentMounts
import com.artemchep.keyguard.common.service.environment.EnvironmentRepository
import com.artemchep.keyguard.common.service.environment.ensureElevatedAccess
import com.artemchep.keyguard.common.service.environment.normalizeEnvironmentMountPath
import com.artemchep.keyguard.common.service.environment.EnvironmentSummary
import com.artemchep.keyguard.common.util.flow.EventFlow
import com.artemchep.keyguard.feature.auth.common.TextFieldModel
import com.artemchep.keyguard.feature.environments.ui.verifyEnvironmentAccess
import com.artemchep.keyguard.feature.filepicker.FilePickerIntent
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.platform.CurrentPlatform
import com.artemchep.keyguard.platform.Platform
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.error_failed_unknown
import com.artemchep.keyguard.ui.FlatItemAction
import com.artemchep.keyguard.util.io.toLocalPathFromFileUriOrNull
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.currentKoinScope

private const val ENV_FILE_NAME = ".env"

private const val FLATPAK_APP_ID = "com.artemchep.keyguard"

@Composable
fun produceEnvironmentMountState(
    environmentId: String?,
): Loadable<EnvironmentMountState> = with(currentKoinScope()) {
    produceEnvironmentMountState(
        environmentId = environmentId,
        environmentRepository = get(),
        environmentAccess = get(),
        environmentMounts = get(),
    )
}

@Composable
fun produceEnvironmentMountState(
    environmentId: String?,
    environmentRepository: EnvironmentRepository,
    environmentAccess: EnvironmentAccess,
    environmentMounts: EnvironmentMounts,
): Loadable<EnvironmentMountState> = produceScreenState(
    key = "environment_mount",
    initial = Loadable.Loading,
    args = arrayOf(
        environmentId,
    ),
) {
    environmentMountStateProducer(
        environmentId = environmentId,
        environmentRepository = environmentRepository,
        environmentAccess = environmentAccess,
        environmentMounts = environmentMounts,
    )
}

private data class EnvironmentMountForm(
    val environmentId: String?,
    val path: String = "",
    val pathRevision: Int = 0,
    val error: Boolean = false,
    val busy: Boolean = false,
) {
    val canCreate: Boolean
        get() = !busy && environmentId != null && normalizeEnvironmentMountPath(path.trim()) != null
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
suspend fun RememberStateFlowScope.environmentMountStateProducer(
    environmentId: String?,
    environmentRepository: EnvironmentRepository,
    environmentAccess: EnvironmentAccess,
    environmentMounts: EnvironmentMounts,
): Flow<Loadable<EnvironmentMountState>> {
    val errorText = translate(Res.string.error_failed_unknown)
    // Portal document paths can not host the pipes, so Flatpak
    // users type a path that is shared with the host instead.
    val flatpak = (CurrentPlatform as? Platform.Desktop.Linux)?.isFlatpak == true

    val formFlow = MutableStateFlow(
        EnvironmentMountForm(
            environmentId = environmentId
                ?: environmentRepository.state.value.environments.firstOrNull()?.id,
        ),
    )
    val filePickerIntentFlow = EventFlow<FilePickerIntent<*>>()

    fun setPath(path: String, programmatic: Boolean) = formFlow.update { form ->
        form.copy(
            path = path,
            pathRevision = if (programmatic) form.pathRevision + 1 else form.pathRevision,
            error = false,
        )
    }

    fun onChooseFolder() {
        val intent = FilePickerIntent.OpenDirectory { result ->
            val directory = result?.uri?.toString()
                ?.toLocalPathFromFileUriOrNull()
                ?.value
                ?: return@OpenDirectory
            setPath("${directory.trimEnd('/')}/$ENV_FILE_NAME", programmatic = true)
        }
        filePickerIntentFlow.emit(intent)
    }

    fun onCreate() {
        val form = formFlow.value
        val id = form.environmentId
            ?: return
        if (form.busy) return
        formFlow.update { it.copy(busy = true, error = false) }
        screenScope.launch {
            try {
                val created = environmentMounts.createWithAccess(
                    access = environmentAccess,
                    environmentId = id,
                    path = form.path.trim(),
                    verifyAccess = {
                        verifyEnvironmentAccess(navigate = ::navigate)
                    },
                )
                if (created) {
                    navigatePopSelf()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                formFlow.update { it.copy(error = true) }
            } finally {
                formFlow.update { it.copy(busy = false) }
            }
        }
    }

    return combine(
        formFlow,
        environmentRepository.state,
    ) { form, state ->
        // The picker is only shown when the environment
        // was not given up front.
        val environment = if (environmentId == null) {
            createEnvironmentPicker(
                environments = state.environments,
                selectedId = form.environmentId,
                onSelect = { id ->
                    formFlow.update { it.copy(environmentId = id, error = false) }
                },
            )
        } else {
            null
        }
        val path = form.path.trim()
            .let { normalizeEnvironmentMountPath(it) ?: it }
        val content = EnvironmentMountState(
            environment = environment,
            path = TextFieldModel(
                text = form.path,
                textRevision = form.pathRevision,
                onChange = if (!form.busy) {
                    // lambda
                    {
                        setPath(it, programmatic = false)
                    }
                } else {
                    null
                },
            ),
            onChooseFolder = if (!flatpak && !form.busy) ::onChooseFolder else null,
            flatpakCommand = if (flatpak) flatpakOverrideCommand(path) else null,
            error = errorText.takeIf { form.error },
            busy = form.busy,
            onCreate = if (form.canCreate) ::onCreate else null,
            filePickerIntentFlow = filePickerIntentFlow,
        )
        Loadable.Ok(content)
    }
}

/**
 * Creates a local `.env` file, verifying the access to the environment
 * first. Returns `false` if the access was not verified.
 */
private suspend fun EnvironmentMounts.createWithAccess(
    access: EnvironmentAccess,
    environmentId: String,
    path: String,
    verifyAccess: suspend () -> Boolean,
): Boolean {
    if (!access.ensureElevatedAccess(environmentId, verify = verifyAccess)) return false
    create(environmentId, path)
    return true
}

private fun createEnvironmentPicker(
    environments: List<EnvironmentSummary>,
    selectedId: String?,
    onSelect: (String) -> Unit,
): EnvironmentMountState.Environment {
    val selected = environments.firstOrNull { it.id == selectedId }
    val dropdown = environments
        .map { environment ->
            FlatItemAction(
                id = "environment.mount.environment.${environment.id}",
                title = TextHolder.Value(environment.name),
                selected = environment.id == selectedId,
                onClick = {
                    onSelect(environment.id)
                },
            )
        }
        .toPersistentList()
    return EnvironmentMountState.Environment(
        name = selected?.name.orEmpty(),
        dropdown = dropdown,
    )
}

/**
 * Grants the Flatpak sandbox access to only the
 * parent folder of the given path.
 */
private fun flatpakOverrideCommand(path: String): String {
    val parent = path
        .substringBeforeLast('/', "")
        .takeIf { it.startsWith('/') }
        ?: "/path/to/project"
    val quoted = "'" + parent.replace("'", "'\"'\"'") + "'"
    return "flatpak override --user --filesystem=$quoted $FLATPAK_APP_ID"
}
