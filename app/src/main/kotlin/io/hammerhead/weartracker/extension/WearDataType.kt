package io.hammerhead.weartracker.extension

import io.hammerhead.weartracker.BikeOffsets
import io.hammerhead.weartracker.WearTrackerRepository
import io.hammerhead.weartracker.WearTrackerState
import io.hammerhead.weartracker.R
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

enum class WearField(val typeId: String, val displayRes: Int, val descRes: Int) {
    CHAIN_DISTANCE("chain-wear", R.string.dt_chain, R.string.dt_chain_desc),
    CHAINRING_TOTAL("chainring-wear", R.string.dt_chainring, R.string.dt_chainring_desc),
    CASSETTE_TOTAL("cassette-wear", R.string.dt_cassette, R.string.dt_cassette_desc),
    RD_TOTAL("rd-wear", R.string.dt_rd, R.string.dt_rd_desc),
    FD_TOTAL("fd-wear", R.string.dt_fd, R.string.dt_fd_desc),
    FRONT_BRAKE("front-brake", R.string.dt_front_brake, R.string.dt_front_brake_desc),
    REAR_BRAKE("rear-brake", R.string.dt_rear_brake, R.string.dt_rear_brake_desc),
    FRONT_TIRE("front-tire", R.string.dt_front_tire, R.string.dt_front_tire_desc),
    REAR_TIRE("rear-tire", R.string.dt_rear_tire, R.string.dt_rear_tire_desc),
    FRONT_SEALANT("front-sealant", R.string.dt_front_sealant, R.string.dt_front_sealant_desc),
    REAR_SEALANT("rear-sealant", R.string.dt_rear_sealant, R.string.dt_rear_sealant_desc),
}

class WearDataType(
    private val karooSystem: KarooSystemService,
    private val repository: WearTrackerRepository,
    extension: String,
    private val field: WearField,
) : DataTypeImpl(extension, field.typeId) {

    override fun startStream(emitter: Emitter<StreamState>) {
        Timber.d("start ${field.typeId} stream")
        val job = CoroutineScope(Dispatchers.IO).launch {
            val isImperial = try {
                karooSystem.consumerFlow<UserProfile>().first()
                    .preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
            } catch (_: Exception) {
                false
            }

            combine(
                karooSystem.consumerFlow<Bikes>(),
                repository.state,
            ) { bikes, wearState ->
                computeValue(bikes, wearState, isImperial)
            }.collect { value ->
                if (value != null) {
                    emitter.onNext(
                        StreamState.Streaming(
                            DataPoint(
                                dataTypeId,
                                mapOf(DataType.Field.SINGLE to value),
                            ),
                        ),
                    )
                } else {
                    emitter.onNext(StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable {
            Timber.d("stop ${field.typeId} stream")
            job.cancel()
        }
    }

    private fun computeValue(bikes: Bikes, state: WearTrackerState, imperial: Boolean): Double? {
        val (bike, offsets) = bikes.bikes.firstNotNullOfOrNull { b ->
            state.bikes[b.id]?.let { b to it }
        } ?: return null
        val odo = bike.odometer

        return when (field) {
            WearField.CHAIN_DISTANCE -> toUserUnit(odo - offsets.chainOdo, imperial)
            WearField.CHAINRING_TOTAL -> toUserUnit(odo - offsets.chainringOdo, imperial)
            WearField.CASSETTE_TOTAL -> toUserUnit(odo - offsets.cassetteOdo, imperial)
            WearField.RD_TOTAL -> toUserUnit(odo - offsets.rearDeraOdo, imperial)
            WearField.FD_TOTAL -> if (offsets.hasFrontDerailleur) {
                toUserUnit(odo - offsets.frontDeraOdo, imperial)
            } else {
                null
            }
            WearField.FRONT_BRAKE -> toUserUnit(odo - offsets.frontBrakeOdo, imperial)
            WearField.REAR_BRAKE -> toUserUnit(odo - offsets.rearBrakeOdo, imperial)
            WearField.FRONT_TIRE -> toUserUnit(odo - offsets.frontTireOdo, imperial)
            WearField.REAR_TIRE -> toUserUnit(odo - offsets.rearTireOdo, imperial)
            WearField.FRONT_SEALANT -> daysSince(offsets.frontSealantDate)
            WearField.REAR_SEALANT -> daysSince(offsets.rearSealantDate)
        }
    }

    companion object {
        private const val METERS_PER_MILE = 1609.344
        private const val METERS_PER_KM = 1000.0

        fun toUserUnit(meters: Double, imperial: Boolean): Double {
            return if (imperial) meters / METERS_PER_MILE else meters / METERS_PER_KM
        }

        fun fromUserUnit(value: Double, imperial: Boolean): Double {
            return if (imperial) value * METERS_PER_MILE else value * METERS_PER_KM
        }

        fun daysSince(timestampMillis: Long): Double? {
            if (timestampMillis == 0L) return null
            val diff = System.currentTimeMillis() - timestampMillis
            return TimeUnit.MILLISECONDS.toDays(diff).toDouble()
        }
    }
}
