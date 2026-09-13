package com.wjf.fadebreak.data

import android.os.Bundle
import com.wjf.fadebreak.core.BreakSettings

/**
 * Cross-process wire format for [BreakSettings]. Both the [SettingsProvider] (main
 * process, owns the DataStore) and the [SettingsBridge] (UI process) use it, so the
 * UI never has to touch the single-process DataStore itself.
 */
internal object SettingsBundle {

    const val KEY_SETTINGS = "settings"

    private const val TIMEOUT = "timeoutMs"
    private const val FADE = "fadeMs"
    private const val FADE_OUT = "fadeOutMs"
    private const val MAX_OPACITY = "maxOpacity"
    private const val MIN_BREAK = "minBreakMs"
    private const val RETRY = "retryMs"
    private const val ENABLED = "enabled"
    private const val WHITELIST = "whitelist"
    private const val BG_IMAGE_ENABLED = "bgImageEnabled"
    private const val BG_IMAGE_URI = "bgImageUri"
    private const val BG_FOLDER_URI = "bgFolderUri"
    private const val BG_FOLDER_INDEX = "bgFolderIndex"
    private const val BG_FOCUS_X = "bgFocusX"
    private const val BG_FOCUS_Y = "bgFocusY"

    fun encode(settings: BreakSettings): Bundle = Bundle().apply {
        putLong(TIMEOUT, settings.timeoutMs)
        putLong(FADE, settings.fadeMs)
        putLong(FADE_OUT, settings.fadeOutMs)
        putFloat(MAX_OPACITY, settings.maxOpacity)
        putLong(MIN_BREAK, settings.minBreakMs)
        putLong(RETRY, settings.retryMs)
        putBoolean(ENABLED, settings.enabled)
        putStringArrayList(WHITELIST, ArrayList(settings.whitelist))
        putBoolean(BG_IMAGE_ENABLED, settings.bgImageEnabled)
        putString(BG_IMAGE_URI, settings.bgImageUri)
        putString(BG_FOLDER_URI, settings.bgFolderUri)
        putInt(BG_FOLDER_INDEX, settings.bgFolderIndex)
        putFloat(BG_FOCUS_X, settings.bgFocusX)
        putFloat(BG_FOCUS_Y, settings.bgFocusY)
    }

    fun decode(bundle: Bundle?): BreakSettings {
        val d = BreakSettings.defaults()
        if (bundle == null) return d
        return BreakSettings(
            timeoutMs = bundle.getLong(TIMEOUT, d.timeoutMs),
            fadeMs = bundle.getLong(FADE, d.fadeMs),
            fadeOutMs = bundle.getLong(FADE_OUT, d.fadeOutMs),
            maxOpacity = bundle.getFloat(MAX_OPACITY, d.maxOpacity),
            minBreakMs = bundle.getLong(MIN_BREAK, d.minBreakMs),
            retryMs = bundle.getLong(RETRY, d.retryMs),
            enabled = bundle.getBoolean(ENABLED, d.enabled),
            whitelist = bundle.getStringArrayList(WHITELIST)?.toSet() ?: d.whitelist,
            bgImageEnabled = bundle.getBoolean(BG_IMAGE_ENABLED, d.bgImageEnabled),
            bgImageUri = bundle.getString(BG_IMAGE_URI) ?: d.bgImageUri,
            bgFolderUri = bundle.getString(BG_FOLDER_URI) ?: d.bgFolderUri,
            bgFolderIndex = bundle.getInt(BG_FOLDER_INDEX, d.bgFolderIndex),
            bgFocusX = bundle.getFloat(BG_FOCUS_X, d.bgFocusX),
            bgFocusY = bundle.getFloat(BG_FOCUS_Y, d.bgFocusY)
        )
    }
}
