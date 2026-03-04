package io.hammerhead.weartracker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max

@Serializable
data class WearTrackerState(
    val bikes: Map<String, BikeOffsets> = emptyMap(),
)

@Serializable
data class BikeOffsets(
    val hasFrontDerailleur: Boolean = false,
    // Odometer reading (meters) at time of component install
    val chainOdo: Double = 0.0,
    val chainringOdo: Double = 0.0,
    val cassetteOdo: Double = 0.0,
    val rearDeraOdo: Double = 0.0,
    val frontDeraOdo: Double = 0.0,
    val frontBrakeOdo: Double = 0.0,
    val rearBrakeOdo: Double = 0.0,
    val frontTireOdo: Double = 0.0,
    val rearTireOdo: Double = 0.0,
    // Max chain distance (meters) ever seen on each drivetrain component
    val chainringMaxChain: Double = 0.0,
    val cassetteMaxChain: Double = 0.0,
    val rearDeraMaxChain: Double = 0.0,
    val frontDeraMaxChain: Double = 0.0,
    // Sealant refresh timestamps (millis since epoch)
    val frontSealantDate: Long = 0,
    val rearSealantDate: Long = 0,
)

class WearTrackerRepository(context: Context) {
    private val prefs = context.getSharedPreferences("weartracker", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(load())
    val state: StateFlow<WearTrackerState> = _state.asStateFlow()

    private fun load(): WearTrackerState {
        val raw = prefs.getString("state", null) ?: return WearTrackerState()
        return try {
            json.decodeFromString<WearTrackerState>(raw)
        } catch (_: Exception) {
            WearTrackerState()
        }
    }

    private fun save(s: WearTrackerState) {
        prefs.edit().putString("state", json.encodeToString(WearTrackerState.serializer(), s)).apply()
        _state.value = s
    }

    private inline fun updateBike(bikeId: String, transform: (BikeOffsets) -> BikeOffsets) {
        val current = _state.value
        val offsets = current.bikes[bikeId] ?: return
        save(current.copy(bikes = current.bikes + (bikeId to transform(offsets))))
    }

    fun initBike(bikeId: String) {
        val current = _state.value
        if (current.bikes.containsKey(bikeId)) return
        // Default offsets are 0: assume all components have been on the bike
        // since the beginning, so wear = full odometer distance.
        // Sealant dates default to 0 (shows N/A until user sets them).
        save(
            current.copy(
                bikes = current.bikes + (bikeId to BikeOffsets()),
            ),
        )
    }

    fun replaceChain(bikeId: String, odometer: Double, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            val chainWear = odometer - offsets.chainOdo
            offsets.copy(
                chainringMaxChain = max(offsets.chainringMaxChain, chainWear),
                cassetteMaxChain = max(offsets.cassetteMaxChain, chainWear),
                rearDeraMaxChain = max(offsets.rearDeraMaxChain, chainWear),
                frontDeraMaxChain = if (offsets.hasFrontDerailleur) {
                    max(offsets.frontDeraMaxChain, chainWear)
                } else {
                    offsets.frontDeraMaxChain
                },
                chainOdo = odometer - resetMeters,
            )
        }
    }

    fun replaceChainring(bikeId: String, odometer: Double, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            offsets.copy(
                chainringOdo = odometer - resetMeters,
                chainringMaxChain = 0.0,
            )
        }
    }

    fun replaceCassette(bikeId: String, odometer: Double, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            offsets.copy(
                cassetteOdo = odometer - resetMeters,
                cassetteMaxChain = 0.0,
            )
        }
    }

    fun replaceRearDera(bikeId: String, odometer: Double, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            offsets.copy(
                rearDeraOdo = odometer - resetMeters,
                rearDeraMaxChain = 0.0,
            )
        }
    }

    fun replaceFrontDera(bikeId: String, odometer: Double, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            offsets.copy(
                frontDeraOdo = odometer - resetMeters,
                frontDeraMaxChain = 0.0,
            )
        }
    }

    fun replaceBrakePads(bikeId: String, odometer: Double, front: Boolean, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            if (front) {
                offsets.copy(frontBrakeOdo = odometer - resetMeters)
            } else {
                offsets.copy(rearBrakeOdo = odometer - resetMeters)
            }
        }
    }

    fun replaceTire(bikeId: String, odometer: Double, front: Boolean, resetMeters: Double = 0.0) {
        updateBike(bikeId) { offsets ->
            if (front) {
                offsets.copy(frontTireOdo = odometer - resetMeters)
            } else {
                offsets.copy(rearTireOdo = odometer - resetMeters)
            }
        }
    }

    fun refreshSealant(bikeId: String, front: Boolean, dateMillis: Long) {
        updateBike(bikeId) { offsets ->
            if (front) {
                offsets.copy(frontSealantDate = dateMillis)
            } else {
                offsets.copy(rearSealantDate = dateMillis)
            }
        }
    }

    fun setHasFrontDerailleur(bikeId: String, has: Boolean) {
        updateBike(bikeId) { offsets ->
            offsets.copy(hasFrontDerailleur = has)
        }
    }
}
