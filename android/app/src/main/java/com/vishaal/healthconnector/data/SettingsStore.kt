package com.vishaal.healthconnector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Encrypted local storage for the small amount of sensitive configuration this app holds:
 * the backend base URL, the bearer token used to authenticate ingest POSTs, and a
 * generated-once device identifier sent with every payload so the backend can distinguish
 * multiple phones/devices syncing to the same account.
 *
 * Backed by [EncryptedSharedPreferences] (AES256-GCM/SIV via a Keystore-backed [MasterKey]).
 */
class SettingsStore(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        // Seed the process-wide theme-mode flow from disk on first construction so any observer
        // (MainActivity) recomposes with the persisted choice.
        themeModeState.value = prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE) ?: DEFAULT_THEME_MODE
    }

    var backendUrl: String?
        get() = prefs.getString(KEY_BACKEND_URL, null)?.trimEnd('/')
        set(value) = prefs.edit().putString(KEY_BACKEND_URL, value?.trimEnd('/')).apply()

    var bearerToken: String?
        get() = prefs.getString(KEY_BEARER_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_BEARER_TOKEN, value).apply()

    /** Stable per-install device id, generated once on first access and cached forever. */
    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (existing != null) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    fun isConfigured(): Boolean = !backendUrl.isNullOrBlank() && !bearerToken.isNullOrBlank()

    /**
     * The user's light/dark preference, stored as one of "SYSTEM" / "LIGHT" / "DARK". Kept as a
     * plain string here (the ThemeMode enum lives in the ui.theme package) and mirrored into a
     * process-wide [StateFlow] so the theme selector in Setup recomposes the whole app instantly.
     */
    var themeMode: String
        get() = themeModeState.value
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
            themeModeState.value = value
        }

    companion object {
        private const val PREFS_FILE_NAME = "health_connector_secure_prefs"
        private const val KEY_BACKEND_URL = "backend_url"
        private const val KEY_BEARER_TOKEN = "bearer_token"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_THEME_MODE = "theme_mode"
        const val DEFAULT_THEME_MODE = "SYSTEM"

        // Process-wide so every SettingsStore instance (each screen builds its own) shares one
        // source of truth; observed by MainActivity to drive HealthConnectorTheme.
        private val themeModeState = MutableStateFlow(DEFAULT_THEME_MODE)
        val themeModeFlow: StateFlow<String> = themeModeState.asStateFlow()
    }
}
