package com.artemchep.keyguard.feature.home.settings.component

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artemchep.keyguard.ui.MediumEmphasisAlpha
import com.artemchep.keyguard.ui.skeleton.SkeletonItem
import com.artemchep.keyguard.ui.theme.Dimens
import com.artemchep.keyguard.ui.theme.combineAlpha
import com.artemchep.keyguard.ui.util.HorizontalDivider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.kodein.di.DirectDI

actual fun settingPermissionOtherProvider(
    directDI: DirectDI,
): SettingComponent = settingPermissionOtherProvider()

fun settingPermissionOtherProvider(): SettingComponent = kotlin.run {
    val item = SettingIi {
        SettingPermissionOther()
    }
    flowOf(item)
}

@Composable
private fun SettingPermissionOther() {
    val context = LocalContext.current
    val permissionsState = remember {
        mutableStateOf<List<List<PermissionItem>>>(emptyList())
    }

    LaunchedEffect(context, permissionsState) {
        val result = withContext(Dispatchers.Default) {
            getPermissionItems(context)
        }
        permissionsState.value = result
    }

    Column(
        modifier = Modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val permissions = permissionsState.value
        if (permissions.isEmpty()) {
            // We assume that our app must have at least one permission,
            // therefore we are still loading the list.
            SkeletonItem()
        }

        permissions.forEachIndexed { index, items ->
            if (index > 0) {
                HorizontalDivider()
            }
            items.forEach { item ->
                key(item.permission) {
                    Column(
                        modifier = Modifier
                            .padding(
                                vertical = 4.dp,
                                horizontal = Dimens.horizontalPadding,
                            ),
                    ) {
                        Text(
                            text = item.permission,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                        )
                        if (item.description != null) {
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current
                                    .combineAlpha(MediumEmphasisAlpha),
                                maxLines = 10,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private data class PermissionItem(
    val permission: String,
    val group: String? = null,
    val description: String? = null,
)

private fun getPermissionItems(context: Context) = kotlin.run {
    val pm = context.packageManager
    val info =
        pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

    val result = info
        .requestedPermissions
        .orEmpty()
        .mapNotNull { permission ->
            kotlin.runCatching {
                val permissionInfo = pm.getPermissionInfo(permission, 0)
                val permissionDescription = permissionInfo.loadDescription(pm)
                    ?.toString()
                    ?.let {
                        // Some of the descriptions do have the dot in the
                        // end, but some of them do. I like when they do.
                        it.removeSuffix(".") + "."
                    }
                PermissionItem(
                    permission = permission,
                    group = permissionInfo.group,
                    description = permissionDescription,
                )
            }.getOrElse {
                PermissionItem(
                    permission = permission,
                )
            }
        }
        .sortedBy { it.permission }
        .groupBy { item ->
            item.group
        }
        .map { (group, items) ->
            items
        }
    result
}
