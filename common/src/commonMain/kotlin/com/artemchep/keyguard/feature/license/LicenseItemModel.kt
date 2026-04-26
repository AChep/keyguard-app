package com.artemchep.keyguard.feature.license

import androidx.compose.runtime.Immutable
import com.artemchep.keyguard.common.service.license.model.License

@Immutable
data class LicenseItemModel(
    val name: String?,
    val dependency: String,
    val licenseNames: List<String>,
    val scmUrl: String?,
)

fun License.toLicenseItemModel(): LicenseItemModel = LicenseItemModel(
    name = name,
    dependency = licenseDependency(this),
    licenseNames = spdxLicenses.map { it.name },
    scmUrl = scm?.url,
)
