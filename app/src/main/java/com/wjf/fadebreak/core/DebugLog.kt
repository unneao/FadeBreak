package com.wjf.fadebreak.core

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.wjf.fadebreak.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only logger. Mirrors to logcat and appends to `filesDir/fadebreak-debug.log`, so
 * events survive a process kill and can be pulled (`adb ... run-as ... cat files/...`)
 * or shared from the settings screen. Completely no-op in release builds.
 */
object DebugLog {

    private const val FILE_NAME = "fadebreak-debug.log"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile
    private var file: File? = null
    private val started = AtomicBoolean(false)
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fadebreak-log").apply { isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        if (!BuildConfig.DEBUG || !started.compareAndSet(false, true)) return
        val target = File(context.filesDir, FILE_NAME)
        file = target
        io.execute {
            runCatching {
                if (target.length() > MAX_BYTES) target.delete()
                target.appendText("===== session start ${timeFormat.format(Date())} =====\n")
            }
        }
    }

    fun currentFile(): File? = if (BuildConfig.DEBUG) file else null

    fun d(message: String) = emit(Log.DEBUG, message)

    fun w(message: String) = emit(Log.WARN, message)

    fun e(message: String, throwable: Throwable? = null) = emit(
        Log.ERROR,
        message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: "")
    )

    private fun emit(priority: Int, message: String) {
        if (!BuildConfig.DEBUG) return
        Log.println(priority, AppConstants.TAG, message)
        val target = file ?: return
        val line = "${timeFormat.format(Date())} rt=${SystemClock.elapsedRealtime()} $message\n"
        io.execute {
            runCatching {
                if (target.length() > MAX_BYTES) {
                    val backup = File(target.parentFile, "$FILE_NAME.1")
                    backup.delete()
                    target.renameTo(backup)
                }
                target.appendText(line)
            }
        }
    }
}
