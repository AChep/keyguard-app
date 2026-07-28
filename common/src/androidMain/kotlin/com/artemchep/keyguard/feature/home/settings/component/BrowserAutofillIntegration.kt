package com.artemchep.keyguard.feature.home.settings.component

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.artemchep.keyguard.android.util.broadcastFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Chromium's third-party Autofill provider contract is documented in the
 * Android Developers update:
 * https://android-developers.googleblog.com/2025/02/chrome-3p-autofill-services-update.html
 */
private const val CONTENT_PROVIDER_NAME = ".AutofillThirdPartyModeContentProvider"
private const val THIRD_PARTY_MODE_COLUMN = "autofill_third_party_state"
private const val THIRD_PARTY_MODE_URI_PATH = "autofill_third_party_mode"

internal enum class BrowserAutofillTarget(
    val displayName: String,
    val packageName: String,
) {
    BRAVE_STABLE(
        displayName = "Brave",
        packageName = "com.brave.browser",
    ),
    CHROME_STABLE(
        displayName = "Chrome",
        packageName = "com.android.chrome",
    ),
    CHROME_BETA(
        displayName = "Chrome Beta",
        packageName = "com.chrome.beta",
    ),
    EDGE_STABLE(
        displayName = "Edge",
        packageName = "com.microsoft.emmx",
    ),
    VIVALDI_STABLE(
        displayName = "Vivaldi",
        packageName = "com.vivaldi.browser",
    ),
}

internal data class BrowserAutofillStatus(
    val target: BrowserAutofillTarget,
    val isEnabled: Boolean,
)

internal data class BrowserAutofillProviderSnapshot(
    val hasFirstRow: Boolean,
    val columnValue: Int?,
)

internal data class BrowserAutofillSettingsRequest(
    val action: String,
    val categories: Set<String>,
    val packageName: String,
)

internal fun BrowserAutofillTarget.providerUri(): String =
    "content://$packageName$CONTENT_PROVIDER_NAME/$THIRD_PARTY_MODE_URI_PATH"

internal fun BrowserAutofillTarget.settingsRequest(
): BrowserAutofillSettingsRequest = BrowserAutofillSettingsRequest(
    action = Intent.ACTION_APPLICATION_PREFERENCES,
    categories = setOf(
        Intent.CATEGORY_DEFAULT,
        Intent.CATEGORY_APP_BROWSER,
        Intent.CATEGORY_PREFERENCE,
    ),
    packageName = packageName,
)

internal fun queryBrowserAutofillState(
    query: () -> BrowserAutofillProviderSnapshot?,
): Boolean? = try {
    val snapshot = query() ?: return null
    if (!snapshot.hasFirstRow) {
        null
    } else {
        snapshot.columnValue?.let { it != 0 }
    }
} catch (_: Exception) {
    null
}

internal suspend fun loadBrowserAutofillStatuses(
    query: suspend (BrowserAutofillTarget) -> Boolean?,
): List<BrowserAutofillStatus> = BrowserAutofillTarget.entries
    .mapNotNull { target ->
        val isEnabled = try {
            query(target)
        } catch (_: Exception) {
            null
        }
        isEnabled?.let {
            BrowserAutofillStatus(
                target = target,
                isEnabled = it,
            )
        }
    }

internal fun observeBrowserAutofillStatuses(
    refreshEvents: Flow<Unit>,
    loadStatuses: suspend () -> List<BrowserAutofillStatus>,
): Flow<List<BrowserAutofillStatus>> = refreshEvents
    .onStart { emit(Unit) }
    .map { loadStatuses() }
    .distinctUntilChanged()

internal fun flowOfBrowserAutofillStatuses(
    context: Context,
): Flow<List<BrowserAutofillStatus>> = observeBrowserAutofillStatuses(
    refreshEvents = browserAutofillRefreshEvents(context),
) {
    withContext(Dispatchers.IO) {
        loadBrowserAutofillStatuses { target ->
            queryBrowserAutofillState(
                contentResolver = context.contentResolver,
                target = target,
            )
        }
    }
}

internal fun Context.launchBrowserAutofillSettings(
    target: BrowserAutofillTarget,
) {
    val request = target.settingsRequest()
    val intent = Intent(request.action).apply {
        request.categories.forEach(::addCategory)
        setPackage(request.packageName)
    }
    startActivity(intent)
}

private fun queryBrowserAutofillState(
    contentResolver: ContentResolver,
    target: BrowserAutofillTarget,
): Boolean? = queryBrowserAutofillState {
    contentResolver
        .query(
            /* uri = */ target.providerUri().toUri(),
            /* projection = */ arrayOf(THIRD_PARTY_MODE_COLUMN),
            /* selection = */ null,
            /* selectionArgs = */ null,
            /* sortOrder = */ null,
        )
        ?.use { cursor ->
            val hasFirstRow = cursor.moveToFirst()
            val columnIndex = if (hasFirstRow) {
                cursor.getColumnIndex(THIRD_PARTY_MODE_COLUMN)
            } else {
                -1
            }
            BrowserAutofillProviderSnapshot(
                hasFirstRow = hasFirstRow,
                columnValue = columnIndex
                    .takeUnless { it == -1 }
                    ?.let(cursor::getInt),
            )
        }
}

private fun browserAutofillRefreshEvents(
    context: Context,
): Flow<Unit> {
    val appResumeEvents = ProcessLifecycleOwner
        .get()
        .lifecycle
        .currentStateFlow
        .drop(1)
        .filter { it == Lifecycle.State.RESUMED }
        .map { Unit }
    val packageChangeEvents = broadcastFlow(
        context = context,
        intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        },
        exported = false,
    )
        .filter { intent ->
            val packageName = intent.data?.schemeSpecificPart
            BrowserAutofillTarget.entries.any { it.packageName == packageName }
        }
        .map { Unit }
    return merge(appResumeEvents, packageChangeEvents)
}
