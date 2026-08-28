package com.ghost.api

import android.app.Application
import timber.log.Timber

class GemmaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global Crash Handler (logs to filesDir/logs/crashes)
        CrashHandler.install(this)

        Timber.plant(Timber.DebugTree())
        Timber.i("Gemma API initialized")
    }
}
