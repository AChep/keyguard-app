package com.artemchep.keyguard.feature.auth

import androidx.compose.runtime.Immutable
import org.jetbrains.compose.resources.DrawableResource

@Immutable
data class AccountViewHeaderState(
    val isLoading: Boolean,
    val logoImageRes: DrawableResource?,
    val host: String?,
    val name: String?,
    val beta: Boolean,
)

fun AccountViewState.toAccountViewHeaderState(): AccountViewHeaderState {
    val data = (content as? AccountViewState.Content.Data)?.data
    return AccountViewHeaderState(
        isLoading = content is AccountViewState.Content.Skeleton,
        logoImageRes = data?.type?.logoImageRes,
        host = data?.host,
        name = data?.type?.fullName,
        beta = data?.type?.beta == true,
    )
}
