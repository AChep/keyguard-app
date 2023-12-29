package com.artemchep.keyguard.feature.appreview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.artemchep.keyguard.common.io.attempt
import com.artemchep.keyguard.common.io.launchIn
import com.artemchep.keyguard.common.usecase.GetShouldRequestAppReview
import com.artemchep.keyguard.common.usecase.RequestAppReview
import com.artemchep.keyguard.platform.LocalLeContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import org.kodein.di.compose.rememberInstance

@Composable
fun RequestAppReviewEffect() {
    val getShouldRequestAppReview: GetShouldRequestAppReview by rememberInstance()
    val requestAppReview: RequestAppReview by rememberInstance()

    val context by rememberUpdatedState(LocalLeContext)
    LaunchedEffect(Unit) {
        val shouldAskForReview = getShouldRequestAppReview()
            .first()
        if (shouldAskForReview) {
            requestAppReview(context)
                .attempt()
                .launchIn(GlobalScope)
        }
    }
}
