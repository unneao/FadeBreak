package com.wjf.fadebreak.data

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.wjf.fadebreak.core.BreakSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-process client for [SettingsProvider]. Mirrors the [SettingsRepository] API
 * (`settings` / `current()` / `set()`) but every call crosses a process boundary, so
 * the `:ui` process never opens the single-process DataStore.
 */
class SettingsBridge(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val uri = SettingsProvider.uri(context.packageName)

    val settings: Flow<BreakSettings> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                launch { trySend(current()) }
            }
        }
        resolver.registerContentObserver(uri, true, observer)
        trySend(current())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    suspend fun current(): BreakSettings = withContext(Dispatchers.IO) { read() }

    /** Whether the accessibility service in the main process is currently connected. */
    suspend fun isServiceAlive(): Boolean = withContext(Dispatchers.IO) {
        resolver.call(uri, SettingsProvider.METHOD_IS_SERVICE_ALIVE, null, null)
            ?.getBoolean(SettingsProvider.KEY_SERVICE_ALIVE)
            ?: false
    }

    suspend fun set(settings: BreakSettings) = withContext(Dispatchers.IO) {
        val extras = Bundle().apply {
            putBundle(SettingsBundle.KEY_SETTINGS, SettingsBundle.encode(settings))
        }
        resolver.call(uri, SettingsProvider.METHOD_SET, null, extras)
        Unit
    }

    private fun read(): BreakSettings =
        resolver.call(uri, SettingsProvider.METHOD_GET, null, null)
            ?.let { SettingsBundle.decode(it) }
            ?: BreakSettings.defaults()
}
