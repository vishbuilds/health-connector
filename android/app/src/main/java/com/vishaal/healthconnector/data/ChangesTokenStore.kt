package com.vishaal.healthconnector.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.syncStateDataStore by preferencesDataStore(name = "sync_state")

/**
 * Persists per-record-type sync state:
 *  - `changesToken`: the Health Connect changes token to resume incremental sync from
 *    (null until the initial backfill for that type has completed).
 *  - `backfillCompleted`: whether [com.vishaal.healthconnector.sync.BackfillWorker] has
 *    finished paging through all historical records of that type.
 *
 * Backed by Jetpack DataStore (Preferences), keyed by each record type's `wireName`
 * (e.g. "StepsRecord") so every record type gets its own pair of keys.
 */
class ChangesTokenStore(private val context: Context) {

    private fun tokenKey(wireName: String) = stringPreferencesKey("changes_token__$wireName")
    private fun backfillKey(wireName: String) = booleanPreferencesKey("backfill_done__$wireName")

    private val lastSyncTimeKey = longPreferencesKey("last_sync_time_millis")

    suspend fun getChangesToken(wireName: String): String? =
        context.syncStateDataStore.data.first()[tokenKey(wireName)]

    suspend fun setChangesToken(wireName: String, token: String?) {
        context.syncStateDataStore.edit { prefs ->
            if (token == null) {
                prefs.remove(tokenKey(wireName))
            } else {
                prefs[tokenKey(wireName)] = token
            }
        }
    }

    suspend fun isBackfillCompleted(wireName: String): Boolean =
        context.syncStateDataStore.data.first()[backfillKey(wireName)] ?: false

    suspend fun setBackfillCompleted(wireName: String, completed: Boolean) {
        context.syncStateDataStore.edit { prefs ->
            prefs[backfillKey(wireName)] = completed
        }
    }

    /**
     * Clears sync state for a single record type so the next backfill pass re-syncs it from
     * scratch. Used when incremental sync hits `ChangesTokenExpiredException` for that type.
     */
    suspend fun clearForRetry(wireName: String) {
        context.syncStateDataStore.edit { prefs ->
            prefs.remove(tokenKey(wireName))
            prefs.remove(backfillKey(wireName))
        }
    }

    suspend fun setLastSyncTimeMillis(millis: Long) {
        context.syncStateDataStore.edit { prefs ->
            prefs[lastSyncTimeKey] = millis
        }
    }

    suspend fun getLastSyncTimeMillis(): Long? =
        context.syncStateDataStore.data.first()[lastSyncTimeKey]

    fun lastSyncTimeMillisFlow(): Flow<Long?> =
        context.syncStateDataStore.data.map { it[lastSyncTimeKey] }

    /** Emits wireName -> backfillCompleted for every known record type, for the status UI. */
    fun backfillStatusFlow(wireNames: List<String>): Flow<Map<String, Boolean>> =
        context.syncStateDataStore.data.map { prefs ->
            wireNames.associateWith { wireName -> prefs[backfillKey(wireName)] ?: false }
        }
}
