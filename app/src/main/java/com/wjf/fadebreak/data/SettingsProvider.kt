package com.wjf.fadebreak.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.wjf.fadebreak.track.ActivityAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Single owner of the settings DataStore. DataStore is **not** safe to open from more
 * than one process, so the UI lives in a separate `:ui` process and talks to this
 * provider (which runs in the default process together with the accessibility service)
 * instead of opening the file itself.
 *
 * Uses [ContentProvider.call] with Bundles for get/set, and [notifyChange] so observers
 * in other processes are told when the store changes (including writes made by the
 * service itself).
 */
class SettingsProvider : ContentProvider() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var repository: SettingsRepository? = null

    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val repo = SettingsRepository(ctx)
        repository = repo
        // Propagate every change (from UI or from the service) to observers across processes.
        scope.launch {
            repo.settings.collect {
                runCatching { ctx.contentResolver.notifyChange(uri(ctx.packageName), null) }
            }
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val repo = repository ?: return null
        return when (method) {
            METHOD_GET -> runBlocking { SettingsBundle.encode(repo.current()) }
            METHOD_SET -> {
                extras?.getBundle(SettingsBundle.KEY_SETTINGS)?.let { bundle ->
                    runBlocking { repo.set(SettingsBundle.decode(bundle)) }
                }
                Bundle.EMPTY
            }
            // The accessibility service shares this process, so it can report whether
            // it is currently connected (i.e. actually working).
            METHOD_IS_SERVICE_ALIVE -> Bundle().apply {
                putBoolean(KEY_SERVICE_ALIVE, ActivityAccessibilityService.instance != null)
            }
            else -> super.call(method, arg, extras)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        const val METHOD_GET = "getSettings"
        const val METHOD_SET = "setSettings"
        const val METHOD_IS_SERVICE_ALIVE = "isServiceAlive"
        const val KEY_SERVICE_ALIVE = "serviceAlive"

        private const val AUTHORITY_SUFFIX = ".settings"

        fun authority(packageName: String): String = "$packageName$AUTHORITY_SUFFIX"

        fun uri(packageName: String): Uri =
            Uri.parse("content://${authority(packageName)}/settings")
    }
}
