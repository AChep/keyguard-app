package com.artemchep.keyguard.feature.qr

import androidx.compose.runtime.Composable
import com.artemchep.keyguard.common.model.Loadable

@Composable
expect fun produceLoadQrState(): Loadable<LoadQrState>
