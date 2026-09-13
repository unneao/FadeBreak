package com.wjf.fadebreak.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wjf.fadebreak.core.BreakSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "fadebreak_settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<BreakSettings> = context.settingsDataStore.data.map { prefs ->
        val d = BreakSettings.defaults()
        BreakSettings(
            timeoutMs = prefs[TIMEOUT] ?: d.timeoutMs,
            fadeMs = prefs[FADE] ?: d.fadeMs,
            fadeOutMs = prefs[FADE_OUT] ?: d.fadeOutMs,
            maxOpacity = prefs[MAX_OPACITY] ?: d.maxOpacity,
            minBreakMs = prefs[MIN_BREAK] ?: d.minBreakMs,
            retryMs = prefs[RETRY] ?: d.retryMs,
            enabled = prefs[ENABLED] ?: d.enabled,
            whitelist = prefs[WHITELIST] ?: d.whitelist,
            bgImageEnabled = prefs[BG_IMAGE_ENABLED] ?: d.bgImageEnabled,
            bgImageUri = prefs[BG_IMAGE_URI] ?: d.bgImageUri,
            bgFolderUri = prefs[BG_FOLDER_URI] ?: d.bgFolderUri,
            bgFolderIndex = prefs[BG_FOLDER_INDEX] ?: d.bgFolderIndex,
            bgFocusX = prefs[BG_FOCUS_X] ?: d.bgFocusX,
            bgFocusY = prefs[BG_FOCUS_Y] ?: d.bgFocusY
        )
    }

    suspend fun set(settings: BreakSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[TIMEOUT] = settings.timeoutMs
            prefs[FADE] = settings.fadeMs
            prefs[FADE_OUT] = settings.fadeOutMs
            prefs[MAX_OPACITY] = settings.maxOpacity
            prefs[MIN_BREAK] = settings.minBreakMs
            prefs[RETRY] = settings.retryMs
            prefs[ENABLED] = settings.enabled
            prefs[WHITELIST] = settings.whitelist
            prefs[BG_IMAGE_ENABLED] = settings.bgImageEnabled
            prefs[BG_IMAGE_URI] = settings.bgImageUri
            prefs[BG_FOLDER_URI] = settings.bgFolderUri
            prefs[BG_FOLDER_INDEX] = settings.bgFolderIndex
            prefs[BG_FOCUS_X] = settings.bgFocusX
            prefs[BG_FOCUS_Y] = settings.bgFocusY
        }
    }

    suspend fun current(): BreakSettings = settings.first()

    private companion object {
        val TIMEOUT = longPreferencesKey("timeoutMs")
        val FADE = longPreferencesKey("fadeMs")
        val FADE_OUT = longPreferencesKey("fadeOutMs")
        val MAX_OPACITY = floatPreferencesKey("maxOpacity")
        val MIN_BREAK = longPreferencesKey("minBreakMs")
        val RETRY = longPreferencesKey("retryMs")
        val ENABLED = booleanPreferencesKey("enabled")
        val WHITELIST = stringSetPreferencesKey("whitelist")
        val BG_IMAGE_ENABLED = booleanPreferencesKey("bgImageEnabled")
        val BG_IMAGE_URI = stringPreferencesKey("bgImageUri")
        val BG_FOLDER_URI = stringPreferencesKey("bgFolderUri")
        val BG_FOLDER_INDEX = intPreferencesKey("bgFolderIndex")
        val BG_FOCUS_X = floatPreferencesKey("bgFocusX")
        val BG_FOCUS_Y = floatPreferencesKey("bgFocusY")
    }
}
