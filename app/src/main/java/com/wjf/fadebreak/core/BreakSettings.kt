package com.wjf.fadebreak.core

data class BreakSettings(
    val timeoutMs: Long,
    val fadeMs: Long,
    val fadeOutMs: Long,
    val maxOpacity: Float,
    val minBreakMs: Long,
    val retryMs: Long,
    val enabled: Boolean = true,
    val whitelist: Set<String> = emptySet(),
    val bgImageEnabled: Boolean = false,
    val bgImageUri: String = "",
    val bgFolderUri: String = "",
    val bgFolderIndex: Int = 0,
    val bgFocusX: Float = 0.5f,
    val bgFocusY: Float = 0.5f
) {
    companion object {
        fun defaults() = BreakSettings(
            timeoutMs = 20 * 60_000L,
            fadeMs = 3_000L,
            fadeOutMs = 3_000L,
            maxOpacity = 1.0f,
            minBreakMs = 20_000L,
            retryMs = 60_000L,
            enabled = true,
            whitelist = emptySet(),
            bgImageEnabled = false,
            bgImageUri = "",
            bgFolderUri = "",
            bgFolderIndex = 0,
            bgFocusX = 0.5f,
            bgFocusY = 0.5f
        )
    }
}
