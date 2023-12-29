package com.artemchep.keyguard.feature.apppicker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.android.util.broadcastFlow
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

private class AppPickerUiException(
    msg: String,
    cause: Throwable,
) : RuntimeException(msg, cause)

@Composable
fun produceAppPickerState(
    transmitter: RouteResultTransmitter<AppPickerResult>,
) = with(localDI().direct) {
    produceAppPickerState(
        transmitter = transmitter,
        unlockUseCase = instance(),
    )
}

@Composable
fun produceAppPickerState(
    transmitter: RouteResultTransmitter<AppPickerResult>,
    unlockUseCase: UnlockUseCase,
): Loadable<AppPickerState> = produceScreenState(
    key = "app_picker",
    initial = Loadable.Loading,
    args = arrayOf(
        unlockUseCase,
    ),
) {
    val queryHandle = searchQueryHandle("query")
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        AppPickerState.Filter(
            revision = revision,
            query = model,
        )
    }

    val appsComparator = Comparator { a: AppInfo, b: AppInfo ->
        a.label.compareTo(b.label, ignoreCase = true)
    }

    fun onClick(appInfo: AppInfo) {
        val uri = "androidapp://${appInfo.packageName}"
        val result = AppPickerResult.Confirm(uri)
        transmitter(result)
        navigatePopSelf()
    }

    fun List<AppInfo>.toItems(): List<AppPickerState.Item> {
        val packageNameCollisions = mutableMapOf<String, Int>()
        return this
            .sortedWith(appsComparator)
            .map { appInfo ->
                val key = kotlin.run {
                    val newPackageNameCollisionCounter = packageNameCollisions
                        .getOrDefault(appInfo.packageName, 0) + 1
                    packageNameCollisions[appInfo.packageName] =
                        newPackageNameCollisionCounter
                    appInfo.packageName + ":" + newPackageNameCollisionCounter
                }
                AppPickerState.Item(
                    key = key,
                    icon = AppIconUrl(
                        packageName = appInfo.packageName,
                    ),
                    name = AnnotatedString(appInfo.label),
                    text = appInfo.packageName,
                    system = appInfo.system,
                    onClick = ::onClick
                        .partially1(appInfo),
                )
            }
    }

    val itemsFlow = getAppsFlow(context.context)
        .map { apps ->
            apps
                .toItems()
                // Index for the search.
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(item.name.text),
                    )
                }
        }
        .mapSearch(
            handle = queryHandle,
        ) { item, result ->
            // Replace the origin text with the one with
            // search decor applied to it.
            item.copy(name = result.highlightedText)
        }
    val contentFlow = itemsFlow
        .crashlyticsAttempt { e ->
            val msg = "Failed to get the app list!"
            AppPickerUiException(
                msg = msg,
                cause = e,
            )
        }
        .map { result ->
            val contentOrException = result
                .map { (items, revision) ->
                    AppPickerState.Content(
                        revision = revision,
                        items = items,
                    )
                }
            Loadable.Ok(contentOrException)
        }
    contentFlow
        .map { content ->
            val state = AppPickerState(
                filter = queryFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}

private fun getAppsFlow(
    context: Context,
) = broadcastFlow(
    context = context,
    intentFilter = IntentFilter().apply {
        addAction(Intent.ACTION_PACKAGE_ADDED)
        addAction(Intent.ACTION_PACKAGE_REPLACED)
        addAction(Intent.ACTION_PACKAGE_REMOVED)
    },
    // Only system can sent those events.
    exported = false,
)
    .map { Unit } // do not care about the intent
    .onStart {
        val initialValue = Unit
        emit(initialValue)
    }
    .map {
        getApps(context)
    }
    .flowOn(Dispatchers.Default)

private fun getApps(
    context: Context,
) = run {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val pm = context.packageManager
    val apps = pm.queryIntentActivities(intent, 0)
    apps
        .map { info ->
            val system = run {
                val ai = pm.getApplicationInfo(info.activityInfo.packageName, 0)
                val mask =
                    ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                ai.flags.and(mask) != 0
            }
            val label = info.loadLabel(pm)?.toString().orEmpty()
            AppInfo(
                packageName = info.activityInfo.packageName,
                label = label,
                system = system,
            )
        }
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val system: Boolean,
)
