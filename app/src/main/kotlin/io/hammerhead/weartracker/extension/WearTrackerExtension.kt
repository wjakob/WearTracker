package io.hammerhead.weartracker.extension

import io.hammerhead.weartracker.WearTrackerApplication
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.SavedDevices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

class WearTrackerExtension : KarooExtension("weartracker", "1.0") {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val repository get() = (application as WearTrackerApplication).repository
    private var serviceJob: Job? = null

    override val types by lazy {
        WearField.entries.map { field ->
            WearDataType(karooSystem, repository, extension, field)
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                Timber.d("WearTracker connected: $connected")
            }

            // Auto-init bikes and auto-detect front derailleur
            launch {
                karooSystem.consumerFlow<Bikes>().collect { event ->
                    for (bike in event.bikes) {
                        if (repository.state.value.bikes[bike.id] == null) {
                            repository.initBike(bike.id)
                        }
                    }
                }
            }

            launch {
                try {
                    val savedDevices = karooSystem.consumerFlow<SavedDevices>().first()
                    val hasFrontDera = savedDevices.devices.any { device ->
                        (device.gearInfo?.maxFrontGears ?: 0) > 1
                    }
                    if (hasFrontDera) {
                        val bikes = karooSystem.consumerFlow<Bikes>().first()
                        for (bike in bikes.bikes) {
                            val offsets = repository.state.value.bikes[bike.id]
                            if (offsets != null && !offsets.hasFrontDerailleur) {
                                repository.setHasFrontDerailleur(bike.id, true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to auto-detect front derailleur")
                }
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        karooSystem.disconnect()
        super.onDestroy()
    }
}
