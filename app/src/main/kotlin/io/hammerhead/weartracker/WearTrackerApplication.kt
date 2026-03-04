package io.hammerhead.weartracker

import android.app.Application
import timber.log.Timber

class WearTrackerApplication : Application() {
    val repository by lazy { WearTrackerRepository(this) }

    override fun onCreate() {
        super.onCreate()
        Timber.plant(Timber.DebugTree())
    }
}
