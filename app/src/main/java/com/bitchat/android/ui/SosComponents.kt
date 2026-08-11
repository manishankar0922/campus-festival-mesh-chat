package com.bitchat.android.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.FestivalChannels
import com.bitchat.android.sos.ActiveSosEntry
import com.bitchat.android.sos.SosPayload
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosConfirmationSheet(
    currentChannel: String?,
    meshService: MeshService,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var locationNote by remember { mutableStateOf("") }
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val startTime = System.currentTimeMillis()
            val duration = 2000L // 2 seconds hold requirement
            while (isHolding && System.currentTimeMillis() - startTime < duration) {
                holdProgress = (System.currentTimeMillis() - startTime).toFloat() / duration.toFloat()
                delay(16)
            }
            if (isHolding) {
                holdProgress = 1f
                val activeChan = currentChannel ?: FestivalChannels.GENERAL
                val sosId = meshService.sendSos(locationNote, activeChan)
                if (sosId != null) {
                    Toast.makeText(context, "Emergency SOS Alert Broadcast!", Toast.LENGTH_LONG).show()
                    onDismiss()
                } else {
                    Toast.makeText(context, "Rate limited: Please wait before sending another SOS", Toast.LENGTH_SHORT).show()
                    isHolding = false
                    holdProgress = 0f
                }
            }
        } else {
            holdProgress = 0f
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "SOS",
                    tint = LocalBitchatPalette.current.accentOrange,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EMERGENCY SOS ALERT",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = BitchatFontFamily
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "This will broadcast an urgent offline emergency alert to all nearby festival attendees over the BLE mesh in #${currentChannel ?: FestivalChannels.GENERAL}.",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = BitchatFontFamily
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = locationNote,
                onValueChange = { if (it.length <= SosPayload.MAX_LOCATION_NOTE_CHARS) locationNote = it },
                label = { Text("Location details (optional, max 60 chars)") },
                placeholder = { Text("e.g., Near Main Stage entrance gate") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("${locationNote.length}/${SosPayload.MAX_LOCATION_NOTE_CHARS}")
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .background(
                        color = LocalBitchatPalette.current.accentOrange.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .border(
                        width = 2.dp,
                        color = LocalBitchatPalette.current.accentOrange,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isHolding = true
                                tryAwaitRelease()
                                isHolding = false
                            }
                        )
                    }
            ) {
                if (holdProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(holdProgress)
                            .align(Alignment.CenterStart)
                            .background(
                                color = LocalBitchatPalette.current.accentOrange,
                                shape = RoundedCornerShape(8.dp)
                            )
                    )
                }
                Text(
                    text = if (holdProgress > 0f) "HOLD TO BROADCAST (${(holdProgress * 100).toInt()}%)" else "HOLD FOR 2s TO BROADCAST SOS",
                    fontWeight = FontWeight.Bold,
                    color = if (holdProgress > 0.5f) Color.White else LocalBitchatPalette.current.accentOrange,
                    fontSize = 14.sp,
                    fontFamily = BitchatFontFamily
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun ActiveSosBanner(
    activeEntries: List<ActiveSosEntry>,
    myPeerId: String,
    onCancelSos: (ActiveSosEntry) -> Unit
) {
    if (activeEntries.isEmpty()) return

    val palette = LocalBitchatPalette.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(palette.accentOrange.copy(alpha = 0.15f))
            .border(width = 1.dp, color = palette.accentOrange)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        activeEntries.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Active SOS",
                    tint = palette.accentOrange,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ACTIVE SOS: ${entry.senderNickname} in #${entry.channel}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = palette.accentOrange,
                        fontFamily = BitchatFontFamily
                    )
                    Text(
                        text = "Location: ${entry.locationNote}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = BitchatFontFamily
                    )
                }
                if (entry.senderPeerId == myPeerId) {
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { onCancelSos(entry) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.accentOrange),
                        border = BorderStroke(1.dp, palette.accentOrange),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Resolve", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
