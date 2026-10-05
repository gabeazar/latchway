package app.latchway.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** What the user can change. Nothing here identifies them. */
data class Settings(
    val rendezvous: String = DEFAULT_RENDEZVOUS,
    val displayName: String = "",
    val relayOnly: Boolean = false,
    val askApproval: Boolean = false,
    val defaultDownloads: Int = 1,
    val defaultExpiryHours: Int = 24,
) {
    companion object {
        const val DEFAULT_RENDEZVOUS = "latchway.app"
    }
}

class SettingsRepo(private val context: Context) {
    private object K {
        val rendezvous = stringPreferencesKey("rendezvous")
        val displayName = stringPreferencesKey("display_name")
        val relayOnly = booleanPreferencesKey("relay_only")
        val askApproval = booleanPreferencesKey("ask_approval")
        val defaultDownloads = intPreferencesKey("default_downloads")
        val defaultExpiryHours = intPreferencesKey("default_expiry_hours")
    }

    val flow: Flow<Settings> = context.store.data.map { p ->
        Settings(
            rendezvous = p[K.rendezvous]?.takeIf { it.isNotBlank() } ?: Settings.DEFAULT_RENDEZVOUS,
            displayName = p[K.displayName] ?: "",
            relayOnly = p[K.relayOnly] ?: false,
            askApproval = p[K.askApproval] ?: false,
            defaultDownloads = p[K.defaultDownloads] ?: 1,
            defaultExpiryHours = p[K.defaultExpiryHours] ?: 24,
        )
    }

    suspend fun current(): Settings = flow.first()

    suspend fun update(fn: (Settings) -> Settings) {
        val next = fn(current())
        context.store.edit { p ->
            p[K.rendezvous] = next.rendezvous.trim()
            p[K.displayName] = next.displayName.trim()
            p[K.relayOnly] = next.relayOnly
            p[K.askApproval] = next.askApproval
            p[K.defaultDownloads] = next.defaultDownloads
            p[K.defaultExpiryHours] = next.defaultExpiryHours
        }
    }
}
