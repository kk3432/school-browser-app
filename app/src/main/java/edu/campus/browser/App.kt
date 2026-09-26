package edu.campus.browser

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.tencent.mmkv.MMKV
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MMKV.initialize(this)
        installCrashHandler()
    }

    /**
     * 全局崩溃捕获：把堆栈写到 filesDir/crash.log，同时用 Toast 显示前几行，
     * 便于现场排查（受管平板不便连 adb）。
     */
    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val trace = sw.toString()
            try {
                val log = File(filesDir, "crash.log")
                log.writeText("时间: ${java.util.Date()}\n线程: ${thread.name}\n\n$trace")
            } catch (_: Throwable) {
            }
            // 主线程弹一下，让老师能看到
            try {
                Handler(Looper.getMainLooper()).post {
                    val head = trace.lineSequence().take(8).joinToString("\n")
                    Toast.makeText(this, "校园浏览器出错了：\n$head", Toast.LENGTH_LONG).show()
                }
                Thread.sleep(1500)
            } catch (_: Throwable) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
