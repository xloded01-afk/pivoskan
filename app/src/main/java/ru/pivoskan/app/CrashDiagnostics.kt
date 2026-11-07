package ru.pivoskan.app

import android.content.Context
import ru.pivoskan.app.data.CrashReport
import java.io.File

object CrashDiagnostics {
    fun install(context: Context) {
        val file = File(context.filesDir, "last-crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText("${System.currentTimeMillis()}\n${CrashReport.format(error)}") }
            if (previous != null) previous.uncaughtException(thread, error)
            else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }
    fun read(context: Context): String? = runCatching {
        File(context.filesDir, "last-crash.txt").takeIf { it.isFile && it.length() <= 64000 }?.readText()
    }.getOrNull()
}
