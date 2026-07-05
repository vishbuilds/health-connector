package com.vishaal.healthconnector

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission

/**
 * Thin wrapper around [HealthConnectClient] centralising:
 *  - SDK availability checks (`getSdkStatus`)
 *  - the full set of permissions this app ever requests (read permission for every
 *    record type in [RecordTypes.ALL], plus the two "special" background/history
 *    permissions)
 *  - checking which of those permissions are currently granted
 *
 * NOTE: [PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND] and [PERMISSION_READ_HEALTH_DATA_HISTORY]
 * are declared here as literal strings rather than referencing SDK constants, because their
 * exact constant names in `androidx.health.connect.client.permission.HealthPermission` have
 * shifted across alpha/rc releases. The string values themselves
 * ("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" and
 * "android.permission.health.READ_HEALTH_DATA_HISTORY") are stable platform permission
 * strings documented by Health Connect and match what's declared in AndroidManifest.xml.
 * Double check these against the `connect-client` version actually pulled in by Gradle.
 */
object HealthConnectManager {

    const val PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND =
        "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    const val PERMISSION_READ_HEALTH_DATA_HISTORY =
        "android.permission.health.READ_HEALTH_DATA_HISTORY"

    /**
     * Health Connect (the provider app) that must be installed on-device. Matches the
     * `<queries>` entry in AndroidManifest.xml.
     */
    const val HEALTH_CONNECT_PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    @Volatile
    private var client: HealthConnectClient? = null

    /** Mirrors [HealthConnectClient.getSdkStatus]; see that method for the meaning of the Int. */
    fun sdkStatus(context: Context): Int =
        HealthConnectClient.getSdkStatus(context, HEALTH_CONNECT_PROVIDER_PACKAGE)

    fun isAvailable(context: Context): Boolean =
        sdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    /** Lazily creates (and caches) the singleton [HealthConnectClient] for this process. */
    fun getClient(context: Context): HealthConnectClient =
        client ?: synchronized(this) {
            client ?: HealthConnectClient.getOrCreate(context).also { client = it }
        }

    /**
     * READ permissions the app depends on to do its core job (sync every readable type to the
     * backend): one per record type in [RecordTypes.ALL] plus the two background/history
     * permissions. These are the permissions that *gate* enabling the sync workers — the app is
     * useless without them.
     */
    fun readPermissions(): Set<String> {
        val recordPermissions = RecordTypes.ALL.map { info ->
            HealthPermission.getReadPermission(info.kClass)
        }
        return (recordPermissions + PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND + PERMISSION_READ_HEALTH_DATA_HISTORY)
            .toSet()
    }

    /**
     * WRITE permissions for the writable subset ([WritableRecordTypes.ALL]) — the types Claude is
     * allowed to write back into Health Connect. These are OPTIONAL: the app requests them, but
     * declining them only disables writes for those types, it doesn't stop read-sync. [WriteWorker]
     * re-checks each type's write permission at drain time.
     */
    fun writePermissions(): Set<String> =
        WritableRecordTypes.ALL.map { HealthPermission.getWritePermission(it.kClass) }.toSet()

    /**
     * The complete permission set this app ever requests: [readPermissions] + [writePermissions].
     * Used to launch the Health Connect permission request. Derived via the SDK's own
     * [HealthPermission.getReadPermission]/[HealthPermission.getWritePermission] so we never
     * hand-mismatch a record type to the wrong permission string.
     */
    fun requiredPermissions(): Set<String> = readPermissions() + writePermissions()

    /** Permissions from [requiredPermissions] that Health Connect currently reports as granted. */
    suspend fun getGrantedPermissions(context: Context): Set<String> {
        val granted = getClient(context).permissionController.getGrantedPermissions()
        return granted.intersect(requiredPermissions())
    }

    suspend fun hasAllPermissions(context: Context): Boolean {
        val granted = getGrantedPermissions(context)
        return granted.containsAll(requiredPermissions())
    }
}
