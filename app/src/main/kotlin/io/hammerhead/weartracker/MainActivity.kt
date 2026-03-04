package io.hammerhead.weartracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hammerhead.weartracker.extension.WearDataType
import io.hammerhead.weartracker.extension.consumerFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.text.NumberFormat
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val karooSystem by lazy { KarooSystemService(this) }
    private val repository get() = (application as WearTrackerApplication).repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bikesFlow = karooSystem.consumerFlow<Bikes>()
            .map<Bikes, List<Bikes.Bike>?> { it.bikes }
            .catch { emit(emptyList()) }

        val isImperialFlow = karooSystem.consumerFlow<UserProfile>()
            .map { it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL }
            .catch { emit(false) }

        setContent {
            DisposableEffect(Unit) {
                karooSystem.connect {}
                onDispose { karooSystem.disconnect() }
            }

            MaterialTheme {
                val bikes by bikesFlow.collectAsStateWithLifecycle(null)
                val wearState by repository.state.collectAsStateWithLifecycle()
                val isImperial by isImperialFlow.collectAsStateWithLifecycle(false)

                WearTrackerScreen(bikes, wearState, isImperial, repository, onBack = { finish() })
            }
        }
    }
}

@Composable
fun WearTrackerScreen(
    bikes: List<Bikes.Bike>?,
    wearState: WearTrackerState,
    isImperial: Boolean,
    repository: WearTrackerRepository,
    onBack: () -> Unit = {},
) {
    val unitLabel = if (isImperial) "mi" else "km"
    val numberFormat = remember { NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            when {
                bikes == null -> {}
                bikes.isEmpty() -> {
                    Text(
                        text = "No bikes configured on Karoo",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                else -> {
                    for ((index, bike) in bikes.withIndex()) {
                        val offsets = wearState.bikes[bike.id] ?: continue
                        BikeSection(bike, offsets, isImperial, unitLabel, numberFormat, repository)
                        if (index < bikes.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }

        // Back button overlay (bottom-left, flush with screen edge)
        val backColor = Color(0xFFA0B4BE)
        val backShape = RoundedCornerShape(
            topStartPercent = 0, bottomStartPercent = 0,
            topEndPercent = 50, bottomEndPercent = 50,
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = 9.dp)
                .background(color = backColor, shape = backShape)
                .clickable(onClick = onBack)
                .padding(start = 12.dp, end = 14.dp, top = 15.dp, bottom = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun BikeSection(
    bike: Bikes.Bike,
    offsets: BikeOffsets,
    isImperial: Boolean,
    unitLabel: String,
    numberFormat: NumberFormat,
    repository: WearTrackerRepository,
) {
    val odo = bike.odometer
    val currentChainWear = odo - offsets.chainOdo

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_weartracker),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = bike.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${formatDistance(odo, isImperial, numberFormat)} $unitLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chain
            ComponentRow(
                iconRes = R.drawable.ic_chain,
                label = "Chain",
                value = formatDistance(currentChainWear, isImperial, numberFormat),
                unit = unitLabel,
                onReplace = { resetMeters ->
                    repository.replaceChain(bike.id, odo, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Chainring
            val chainringOldestChain = max(offsets.chainringMaxChain, currentChainWear)
            ComponentRow(
                iconRes = R.drawable.ic_chainring,
                label = "Chainring",
                value = formatDistance(odo - offsets.chainringOdo, isImperial, numberFormat),
                unit = unitLabel,
                subtitle = "Oldest chain: ${formatDistance(chainringOldestChain, isImperial, numberFormat)} $unitLabel",
                onReplace = { resetMeters ->
                    repository.replaceChainring(bike.id, odo, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Cassette
            val cassetteOldestChain = max(offsets.cassetteMaxChain, currentChainWear)
            ComponentRow(
                iconRes = R.drawable.ic_cassette,
                label = "Cassette",
                value = formatDistance(odo - offsets.cassetteOdo, isImperial, numberFormat),
                unit = unitLabel,
                subtitle = "Oldest chain: ${formatDistance(cassetteOldestChain, isImperial, numberFormat)} $unitLabel",
                onReplace = { resetMeters ->
                    repository.replaceCassette(bike.id, odo, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Rear derailleur
            val rdOldestChain = max(offsets.rearDeraMaxChain, currentChainWear)
            ComponentRow(
                iconRes = R.drawable.ic_derailleur,
                label = "Rear derailleur",
                value = formatDistance(odo - offsets.rearDeraOdo, isImperial, numberFormat),
                unit = unitLabel,
                subtitle = "Oldest chain: ${formatDistance(rdOldestChain, isImperial, numberFormat)} $unitLabel",
                onReplace = { resetMeters ->
                    repository.replaceRearDera(bike.id, odo, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Front derailleur (only when detected)
            if (offsets.hasFrontDerailleur) {
                val fdOldestChain = max(offsets.frontDeraMaxChain, currentChainWear)
                ComponentRow(
                    iconRes = R.drawable.ic_derailleur,
                    label = "Front derailleur",
                    value = formatDistance(odo - offsets.frontDeraOdo, isImperial, numberFormat),
                    unit = unitLabel,
                    subtitle = "Oldest chain: ${formatDistance(fdOldestChain, isImperial, numberFormat)} $unitLabel",
                    onReplace = { resetMeters ->
                        repository.replaceFrontDera(bike.id, odo, resetMeters)
                    },
                    unitLabel = unitLabel,
                )
            }

            // Front brake pads
            ComponentRow(
                iconRes = R.drawable.ic_brake,
                label = "Front brake pads",
                value = formatDistance(odo - offsets.frontBrakeOdo, isImperial, numberFormat),
                unit = unitLabel,
                onReplace = { resetMeters ->
                    repository.replaceBrakePads(bike.id, odo, front = true, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Rear brake pads
            ComponentRow(
                iconRes = R.drawable.ic_brake,
                label = "Rear brake pads",
                value = formatDistance(odo - offsets.rearBrakeOdo, isImperial, numberFormat),
                unit = unitLabel,
                onReplace = { resetMeters ->
                    repository.replaceBrakePads(bike.id, odo, front = false, resetMeters)
                },
                unitLabel = unitLabel,
            )

            // Front tire
            ComponentRow(
                iconRes = R.drawable.ic_tire,
                label = "Front tire",
                value = formatDistance(odo - offsets.frontTireOdo, isImperial, numberFormat),
                unit = unitLabel,
                onReplace = { resetMeters ->
                    repository.replaceTire(bike.id, odo, front = true, resetMeters)
                },
                unitLabel = unitLabel,
            )
            SealantRow(
                label = "Sealant",
                dateMillis = offsets.frontSealantDate,
                onRefresh = { dateMillis ->
                    repository.refreshSealant(bike.id, front = true, dateMillis)
                },
            )

            // Rear tire
            ComponentRow(
                iconRes = R.drawable.ic_tire,
                label = "Rear tire",
                value = formatDistance(odo - offsets.rearTireOdo, isImperial, numberFormat),
                unit = unitLabel,
                onReplace = { resetMeters ->
                    repository.replaceTire(bike.id, odo, front = false, resetMeters)
                },
                unitLabel = unitLabel,
            )
            SealantRow(
                label = "Sealant",
                dateMillis = offsets.rearSealantDate,
                onRefresh = { dateMillis ->
                    repository.refreshSealant(bike.id, front = false, dateMillis)
                },
            )
        }
    }
}

@Composable
fun ComponentRow(
    iconRes: Int,
    label: String,
    value: String,
    unit: String,
    subtitle: String? = null,
    onReplace: (Double) -> Unit,
    unitLabel: String,
) {
    var showDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$value $unit",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable { showDialog = true },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "\u21BB",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
    }

    if (showDialog) {
        ReplaceDialog(
            componentName = label,
            unitLabel = unitLabel,
            onDismiss = { showDialog = false },
            onConfirm = { resetValue ->
                val resetMeters = WearDataType.fromUserUnit(resetValue, unitLabel == "mi")
                onReplace(resetMeters)
                showDialog = false
            },
        )
    }
}

@Composable
fun ReplaceDialog(
    componentName: String,
    unitLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit,
) {
    var resetText by remember { mutableStateOf("0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Replace $componentName",
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reset to",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedTextField(
                    value = resetText,
                    onValueChange = { resetText = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(100.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    unitLabel,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val value = resetText.toDoubleOrNull() ?: 0.0
                onConfirm(value)
            }) {
                Text("Replace")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
fun SealantRow(
    label: String,
    dateMillis: Long,
    onRefresh: (Long) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val days = if (dateMillis > 0) {
        val diff = System.currentTimeMillis() - dateMillis
        java.util.concurrent.TimeUnit.MILLISECONDS.toDays(diff)
    } else null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp, top = 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_sealant),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "$label: ${days ?: "\u2014"} days",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(
            onClick = { showDialog = true },
            modifier = Modifier.size(32.dp),
        ) {
            Text(
                text = "\uD83D\uDCA7",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showDialog) {
        var daysAgoText by remember { mutableStateOf("0") }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(
                    "Refresh sealant",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = daysAgoText,
                        onValueChange = { daysAgoText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(80.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "days ago",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val daysAgo = daysAgoText.toLongOrNull() ?: 0L
                    val millis = System.currentTimeMillis() - daysAgo * 86_400_000L
                    onRefresh(millis)
                    showDialog = false
                }) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun formatDistance(meters: Double, isImperial: Boolean, numberFormat: NumberFormat): String {
    val value = WearDataType.toUserUnit(meters, isImperial)
    return numberFormat.format(value)
}
