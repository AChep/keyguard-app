package com.artemchep.keyguard.feature.home.settings.accounts.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FilePresent
import androidx.compose.ui.graphics.vector.ImageVector
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.addaccount_description_short_bitwarden_text
import com.artemchep.keyguard.res.addaccount_description_short_keepass_text
import com.artemchep.keyguard.res.ic_logo_bitwarden
import com.artemchep.keyguard.res.ic_logo_keepass
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource

enum class AccountType(
    val fullName: String,
    val descriptionRes: StringResource,
    val beta: Boolean = false,
    val local: Boolean = false,
    val logoImageRes: DrawableResource,
    val iconImageVector: ImageVector,
) {
    BITWARDEN(
        fullName = "Bitwarden",
        descriptionRes = Res.string.addaccount_description_short_bitwarden_text,
        logoImageRes = Res.drawable.ic_logo_bitwarden,
        iconImageVector = Icons.Outlined.Cloud,
    ),
    KEEPASS(
        fullName = "KeePass",
        descriptionRes = Res.string.addaccount_description_short_keepass_text,
        beta = true,
        local = true,
        logoImageRes = Res.drawable.ic_logo_keepass,
        iconImageVector = Icons.Outlined.FilePresent,
    ),
}
