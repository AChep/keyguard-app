package com.artemchep.keyguard.feature.apppicker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager.NameNotFoundException
import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import arrow.core.partially1
import com.artemchep.keyguard.android.util.broadcastFlow
import com.artemchep.keyguard.common.model.Loadable
import com.artemchep.keyguard.common.usecase.UnlockUseCase
import com.artemchep.keyguard.common.util.PROTOCOL_ANDROID_APP
import com.artemchep.keyguard.feature.apppicker.model.AppPickerSortItem
import com.artemchep.keyguard.feature.crashlytics.crashlyticsAttempt
import com.artemchep.keyguard.feature.favicon.AppIconUrl
import com.artemchep.keyguard.feature.home.vault.search.IndexedText
import com.artemchep.keyguard.feature.home.vault.search.sort.AlphabeticalSort
import com.artemchep.keyguard.feature.localization.TextHolder
import com.artemchep.keyguard.feature.navigation.RouteResultTransmitter
import com.artemchep.keyguard.feature.navigation.state.navigatePopSelf
import com.artemchep.keyguard.feature.navigation.state.produceScreenState
import com.artemchep.keyguard.feature.search.search.IndexedModel
import com.artemchep.keyguard.feature.search.search.mapSearch
import com.artemchep.keyguard.feature.search.search.searchFilter
import com.artemchep.keyguard.feature.search.search.searchQueryHandle
import com.artemchep.keyguard.platform.parcelize.LeIgnoredOnParcel
import com.artemchep.keyguard.platform.parcelize.LeParcelable
import com.artemchep.keyguard.platform.parcelize.LeParcelize
import com.artemchep.keyguard.res.Res
import com.artemchep.keyguard.res.*
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import org.jetbrains.compose.resources.StringResource
import org.kodein.di.compose.localDI
import org.kodein.di.direct
import org.kodein.di.instance

@LeParcelize
@Serializable
data class AppPickerComparatorHolder(
    val comparator: AppPickerSort,
    val reversed: Boolean = false,
) : LeParcelable {
    companion object {
        fun of(map: Map<String, Any?>): AppPickerComparatorHolder {
            return AppPickerComparatorHolder(
                comparator = AppPickerSort.valueOf(map["comparator"].toString())
                    ?: AppPickerAlphabeticalSort,
                reversed = map["reversed"].toString() == "true",
            )
        }
    }

    fun toMap() = mapOf(
        "comparator" to comparator.id,
        "reversed" to reversed,
    )
}

interface AppPickerSort : Comparator<AppInfo>, Parcelable {
    val id: String

    companion object {
        fun valueOf(
            name: String,
        ): AppPickerSort? = when (name) {
            AppPickerAlphabeticalSort.id -> AppPickerAlphabeticalSort
            AppPickerInstallTimeSort.id -> AppPickerInstallTimeSort
            else -> null
        }
    }
}

@LeParcelize
@Serializable
data object AppPickerAlphabeticalSort : AppPickerSort {
    @LeIgnoredOnParcel
    @Transient
    override val id: String = "alphabetical"

    override fun compare(
        a: AppInfo,
        b: AppInfo,
    ): Int = kotlin.run {
        val aTitle = a.label
        val bTitle = b.label
        AlphabeticalSort.compareStr(aTitle, bTitle)
    }
}

@LeParcelize
@Serializable
data object AppPickerInstallTimeSort : AppPickerSort {
    @LeIgnoredOnParcel
    @Transient
    override val id: String = "install_time"

    override fun compare(
        a: AppInfo,
        b: AppInfo,
    ): Int = kotlin.run {
        val aInstallTime = a.installTime
        val bInstallTime = b.installTime
        -aInstallTime.compareTo(bInstallTime)
    }
}

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
    val sortDefault = AppPickerComparatorHolder(
        comparator = AppPickerAlphabeticalSort,
    )
    val sortSink = mutablePersistedFlow(
        key = "sort",
        serialize = { _, value ->
            value.toMap()
        },
        deserialize = { _, value ->
            AppPickerComparatorHolder.of(value)
        },
    ) {
        sortDefault
    }

    val queryHandle = searchQueryHandle(
        key = "query",
        revisionFlow = sortSink
            .map { it.hashCode() },
    )
    val queryFlow = searchFilter(queryHandle) { model, revision ->
        AppPickerState.Filter(
            revision = revision,
            query = model,
        )
    }

    fun onClearSort() {
        sortSink.value = sortDefault
    }

    fun createComparatorAction(
        id: String,
        title: StringResource,
        icon: ImageVector? = null,
        config: AppPickerComparatorHolder,
    ) = AppPickerSortItem.Item(
        id = id,
        config = config,
        title = TextHolder.Res(title),
        icon = icon,
        onClick = {
            sortSink.value = config
        },
        checked = false,
    )

    data class AppPickerComparatorSortGroup(
        val item: AppPickerSortItem.Item,
        val subItems: List<AppPickerSortItem.Item>,
    )

    val cam = mapOf(
        AppPickerAlphabeticalSort to AppPickerComparatorSortGroup(
            item = createComparatorAction(
                id = "title",
                icon = Icons.Outlined.SortByAlpha,
                title = Res.string.sortby_title_title,
                config = AppPickerComparatorHolder(
                    comparator = AppPickerAlphabeticalSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "title_normal",
                    title = Res.string.sortby_title_normal_mode,
                    config = AppPickerComparatorHolder(
                        comparator = AppPickerAlphabeticalSort,
                    ),
                ),
                createComparatorAction(
                    id = "title_rev",
                    title = Res.string.sortby_title_reverse_mode,
                    config = AppPickerComparatorHolder(
                        comparator = AppPickerAlphabeticalSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
        AppPickerInstallTimeSort to AppPickerComparatorSortGroup(
            item = createComparatorAction(
                id = "modify_date",
                icon = Icons.Outlined.CalendarMonth,
                title = Res.string.sortby_installation_date_title,
                config = AppPickerComparatorHolder(
                    comparator = AppPickerInstallTimeSort,
                ),
            ),
            subItems = listOf(
                createComparatorAction(
                    id = "modify_date_normal",
                    title = Res.string.sortby_installation_date_normal_mode,
                    config = AppPickerComparatorHolder(
                        comparator = AppPickerInstallTimeSort,
                    ),
                ),
                createComparatorAction(
                    id = "modify_date_rev",
                    title = Res.string.sortby_installation_date_reverse_mode,
                    config = AppPickerComparatorHolder(
                        comparator = AppPickerInstallTimeSort,
                        reversed = true,
                    ),
                ),
            ),
        ),
    )

    val sortFlow = sortSink
        .map { orderConfig ->
            val mainItems = cam.values
                .map { it.item }
                .map { item ->
                    val checked = item.config.comparator == orderConfig.comparator
                    item.copy(checked = checked)
                }
            val subItems = cam[orderConfig.comparator]?.subItems.orEmpty()
                .map { item ->
                    val checked = item.config == orderConfig
                    item.copy(checked = checked)
                }

            val out = mutableListOf<AppPickerSortItem>()
            out += mainItems
            if (subItems.isNotEmpty()) {
                out += AppPickerSortItem.Section(
                    id = "sub_items_section",
                    text = TextHolder.Res(Res.string.options),
                )
                out += subItems
            }

            AppPickerState.Sort(
                sort = out.toPersistentList(),
                clearSort = if (orderConfig != sortDefault) {
                    ::onClearSort
                } else null,
            )
        }
        .stateIn(screenScope)

    fun onClick(appInfo: AppInfo) {
        val uri = "$PROTOCOL_ANDROID_APP${appInfo.packageName}"
        val result = AppPickerResult.Confirm(uri)
        transmitter(result)
        navigatePopSelf()
    }

    fun List<AppInfo>.toItems(): List<AppPickerState.Item> {
        val packageNameCollisions = mutableMapOf<String, Int>()
        return this
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

    val itemsFlow = flowOfInstalledApps(context.context)
        .combine(sortSink) { items, sort ->
            val comparator = Comparator<AppInfo> { a, b ->
                val result = sort.comparator.compare(a, b)
                if (sort.reversed) -result else result
            }
            val sortedItems = items
                .sortedWith(comparator)
                .toItems()
                // Index for the search.
                .map { item ->
                    IndexedModel(
                        model = item,
                        indexedText = IndexedText.invoke(item.name.text),
                    )
                }
            sortedItems
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
                sort = sortFlow,
                content = content,
            )
            Loadable.Ok(state)
        }
}

fun flowOfInstalledAppsAnyOf(
    context: Context,
    packageNames: Iterable<String>,
) = flowOfInstalledApps(context)
    .map { installedApps ->
        installedApps
            .any { app -> app.packageName in packageNames }
    }
    .distinctUntilChanged()

fun flowOfInstalledApps(
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
        .mapNotNull { info ->
            // FIXME: Double check that this is a correct way to get the info about the package.
            //  I saw a crash that points out to 'app.lawnchair' which does not exist on
            //  the Google Play Store.
            val packageInfo: PackageInfo
            val applicationInfo: ApplicationInfo
            try {
                packageInfo = pm.getPackageInfo(info.activityInfo.packageName, 0)
                applicationInfo = pm.getApplicationInfo(info.activityInfo.packageName, 0)
            } catch (e: NameNotFoundException) {
                // The app was removed in-between querying the list
                // of activities and querying the info about each
                // of the app.
                return@mapNotNull null
            }
            val system = run {
                val mask =
                    ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
                applicationInfo.flags.and(mask) != 0
            }
            val label = info.loadLabel(pm).toString()
            val installTime = packageInfo.firstInstallTime
            AppInfo(
                packageName = info.activityInfo.packageName,
                label = label,
                system = system,
                installTime = installTime,
            )
        }
}

data class AppInfo(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val installTime: Long,
)
