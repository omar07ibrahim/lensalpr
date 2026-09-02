package com.lensalpr.app

import android.app.Application
import android.util.Log

class LensAlprApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Installed before anything else can throw: a session that dies unattended on the rear
        // window must leave a trace the next start can deliver.
        CrashReporter.install(this)
        Log.i(
            "LensALPR",
            "start ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                "sdk=${android.os.Build.VERSION.SDK_INT}",
        )
    }
}
