package com.yuno.tv.core.config

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yuno.tv.core.model.PlayerSettings
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.yunoSettings by preferencesDataStore(name = "player_settings")
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.yunoSettings
    private val speed = floatPreferencesKey("speed")
    private val aspect = stringPreferencesKey("aspect")
    private val seek = intPreferencesKey("seekSeconds")
    private val history = booleanPreferencesKey("history")
    private val next = booleanPreferencesKey("autoNext")
    private val refresh = booleanPreferencesKey("autoRefresh")
    private val theme = stringPreferencesKey("theme")
    private val imported = booleanPreferencesKey("bundleImported")
    private val destination = stringPreferencesKey("lastDestination")
    private val home = stringPreferencesKey("homeCategory")
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        PlayerSettings(it[speed] ?: 1f, it[aspect] ?: "FIT", it[seek] ?: 10, it[history] ?: true,
            it[next] ?: true, it[refresh] ?: true, it[theme] ?: "DARK", it[home] ?: "recent", it[imported] ?: false, it[destination] ?: "HOME")
    }
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        store.edit { values ->
            val current = PlayerSettings(values[speed] ?: 1f, values[aspect] ?: "FIT", values[seek] ?: 10,
                values[history] ?: true, values[next] ?: true, values[refresh] ?: true,
                values[theme] ?: "DARK", values[home] ?: "recent", values[imported] ?: false, values[destination] ?: "HOME")
            val new = transform(current)
            values[speed] = new.speed; values[aspect] = new.aspect; values[seek] = new.seekSeconds
            values[history] = new.historyEnabled; values[next] = new.autoNext; values[refresh] = new.autoRefresh
            values[theme] = new.theme; values[home] = new.homeCategory
            values[imported] = new.bundleImported; values[destination] = new.lastDestination
        }
    }
}
