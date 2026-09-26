package com.artemchep.keyguard.feature.license

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.feature.navigation.state.RememberStateFlowScope
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.currentKoinScope

@Composable
fun produceLicenseState(
) = with(currentKoinScope()) {
    produceLicenseState(
        licenseService = get(),
    )
}

@Composable
fun produceLicenseState(
    licenseService: LicenseService,
): Loadable<LicenseState> = produceScreenState(
    key = "open_source_licenses",
    initial = Loadable.Loading,
    args = arrayOf(
        licenseService,
    ),
) {
    licenseStateProducer(
        licenseService = licenseService,
    )
}

suspend fun RememberStateFlowScope.licenseStateProducer(
    licenseService: LicenseService,
): Flow<Loadable<LicenseState>> {
    val request = licenseService.get()
        .attempt()
        .bind()
    request.isLeft {
        it.printStackTrace()
        true
    }

    val content = LicenseState.Content(
        items = request
            .getOrNull()
            .orEmpty(),
    )
    val state = LicenseState(
        content = content,
    )
    return flowOf(Loadable.Ok(state))
}
