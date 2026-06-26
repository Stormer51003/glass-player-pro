package com.stormer.glassplayer

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class CrashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val log = "Glass Player Pro crash:\n\n${sw}"
                File(filesDir, "last_crash.txt").writeText(log)
            } catch (_: Exception) {}
            previous?.uncaughtException(thread, throwable)
        }
    }
}
