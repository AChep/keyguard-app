package com.artemchep.keyguard.feature.license

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.bind
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.service.license.LicenseService
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import kotlinx.coroutines.flow.flowOf
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@Composable
fun produceLicenseState(
) = with(localDI().direct) {
    produceLicenseState(
        licenseService = instance(),
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
    flowOf(Loadable.Ok(state))
}
