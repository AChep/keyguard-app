package com.artemchep.keyguard.common.usecase

import com.artemchep.keyguard.common.model.DFilter
import com.artemchep.keyguard.common.model.DFolder
import com.artemchep.keyguard.common.model.DSecret
import com.artemchep.keyguard.common.model.DSend
import com.artemchep.keyguard.common.model.DSendFilter
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Provides a list of all available on the
 * device accounts.
 */
interface GetCiphers : () -> Flow<List<DSecret>>

fun filterHiddenProfiles(
    getCiphers: GetCiphers,
    getProfiles: GetProfiles,
    filter: DFilter? = null,
): Flow<List<DSecret>> {
    val shouldFilter = filter
        ?.let { f ->
            // If we have a pre-set filter that specifies an
            // identifier of the folder or collection or account etc,
            // then we ignore the hidden account setting.
            DFilter.findOne<DFilter.ById>(f) { true } == null
        }
        ?: true
    return if (shouldFilter) {
        _filterHiddenProfiles(
            getItems = getCiphers,
            getProfiles = getProfiles,
            getAccount = { it.accountId },
        )
    } else {
        getCiphers()
    }
}

fun filterHiddenProfiles(
    getFolders: GetFolders,
    getProfiles: GetProfiles,
    filter: DFilter? = null,
): Flow<List<DFolder>> {
    val shouldFilter = filter
        ?.let { f ->
            // If we have a pre-set filter that specifies an
            // identifier of the folder or collection or account etc,
            // then we ignore the hidden account setting.
            DFilter.findOne<DSendFilter.ById>(f) { true } == null
        }
        ?: true
    return if (shouldFilter) {
        _filterHiddenProfiles(
            getItems = getFolders,
            getProfiles = getProfiles,
            getAccount = { it.accountId },
        )
    } else {
        getFolders()
    }
}

fun filterHiddenProfiles(
    getSends: GetSends,
    getProfiles: GetProfiles,
    filter: DSendFilter? = null,
): Flow<List<DSend>> {
    val shouldFilter = filter
        ?.let { f ->
            // If we have a pre-set filter that specifies an
            // identifier of the folder or collection or account etc,
            // then we ignore the hidden account setting.
            DSendFilter.findOne<DSendFilter.ById>(f) { true } == null
        }
        ?: true
    return if (shouldFilter) {
        _filterHiddenProfiles(
            getItems = getSends,
            getProfiles = getProfiles,
            getAccount = { it.accountId },
        )
    } else {
        getSends()
    }
}

private inline fun <T> _filterHiddenProfiles(
    getItems: () -> Flow<List<T>>,
    getProfiles: GetProfiles,
    crossinline getAccount: (T) -> String,
): Flow<List<T>> {
    val ccc = getProfiles()
        .map { profiles ->
            profiles
                .asSequence()
                .filter { it.hidden }
                .map { it.accountId }
                .toPersistentSet()
        }
        .distinctUntilChanged()
    return getItems()
        .combine(ccc) { items, hiddenAccountIds ->
            items
                .filter { getAccount(it) !in hiddenAccountIds }
        }
}
