package app.zemote

import android.app.Application
import app.zemote.crash.CrashHandler

/**
 * Application class for Zemote.
 * 安装全局崩溃捕获：崩溃日志写入本地，下次启动进入崩溃报告页。
 */
class ZemoteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
