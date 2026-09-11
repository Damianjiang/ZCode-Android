package app.zemote.crash

import android.content.Context
import android.os.Build
import android.util.Log
import app.zemote.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：安装默认未捕获异常处理器，崩溃时把完整日志写入
 * filesDir/crash_report.txt，下次启动由 MainActivity 检测并展示崩溃页。
 */
object CrashHandler {
    private const val FILE_NAME = "crash_report.txt"

    fun install(context: Context) {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                File(context.filesDir, FILE_NAME).writeText(buildReport(thread, throwable))
            } catch (_: Exception) {
                // 崩溃处理自身不能再崩
            }
            // 交还系统默认处理（结束进程）
            systemHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun buildReport(thread: Thread, throwable: Throwable): String = buildString {
        append("Zemote 崩溃报告\n")
        append("════════════════════════\n")
        append("时间: ").append(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        ).append('\n')
        append("版本: ").append(BuildConfig.VERSION_NAME)
            .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
        append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("系统: Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("线程: ").append(thread.name).append("\n\n")
        append(Log.getStackTraceString(throwable))
        // 附上根因，便于定位 ExceptionInInitializerError 之类的问题
        var cause = throwable.cause
        while (cause != null) {
            append("\n─── Caused by ───\n")
            append(Log.getStackTraceString(cause))
            cause = cause.cause
        }
    }

    fun read(context: Context): String? = try {
        File(context.filesDir, FILE_NAME).readText().takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
