// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.ui.calls

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.R
import app.nectarlink.android.calls.ActiveCallManager
import app.nectarlink.android.calls.ActiveCallState
import kotlinx.coroutines.delay

/**
 * In-call controls and DTMF keypad card, matching the NectarLink desktop in-call design.
 * Lets the user mute, toggle speaker, hold, and dial DTMF digits (1-9, *, 0, #) for IVR menus.
 */
@Composable
fun ActiveCallCard(
    call: ActiveCallState,
    modifier: Modifier = Modifier,
    initialKeypadOpen: Boolean = false,
) {
    var keypadOpen by remember { mutableStateOf(initialKeypadOpen) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(call.since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val mins = (totalSecs % 3600) / 60
        val secs = totalSecs % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%d:%02d", mins, secs)
        }
    }

    val durationText = remember(now, call.since, call.isHeld) {
        if (call.isHeld) "ON HOLD"
        else formatDuration(now - call.since)
    }

    val photoBitmap = remember(call.photo) {
        call.photo?.let { bytes ->
            if (bytes.isNotEmpty()) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            } else null
        }
    }

    val displayName = call.name?.takeIf(String::isNotBlank)
        ?: call.number.takeIf(String::isNotBlank)
        ?: "Unknown Caller"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header: Status and Clock
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (call.deviceName != null) "CALL ON ${call.deviceName.uppercase()}" else "ON A CALL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (call.isHeld) Color(0xFFF59E0B) else MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(14.dp))

            // Caller Info Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val initial = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()
                        if (initial != null) {
                            Text(
                                text = initial.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    if (call.pressedDigits.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = call.pressedDigits,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            IconButton(
                                onClick = { ActiveCallManager.backspaceDtmf() },
                                modifier = Modifier.size(22.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_backspace),
                                    contentDescription = "Backspace",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else if (!call.name.isNullOrBlank() && call.number.isNotBlank()) {
                        Text(
                            text = call.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // In-Call Controls Row: MUTE, SPEAKER, HOLD, KEYPAD
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mute Control
                CallControlButton(
                    iconRes = if (call.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                    label = if (call.isMuted) "Muted" else "Mute",
                    active = call.isMuted,
                    onClick = { ActiveCallManager.setMute(!call.isMuted) },
                )

                // Speaker Control
                CallControlButton(
                    iconRes = R.drawable.ic_speaker,
                    label = "Speaker",
                    active = call.isSpeaker,
                    onClick = { ActiveCallManager.setSpeaker(!call.isSpeaker) },
                )

                // Hold Control
                if (call.canHold || call.isHeld) {
                    CallControlButton(
                        iconRes = if (call.isHeld) R.drawable.ic_play else R.drawable.ic_pause,
                        label = if (call.isHeld) "Resume" else "Hold",
                        active = call.isHeld,
                        onClick = { ActiveCallManager.setHold(!call.isHeld) },
                    )
                }

                // Keypad Toggle
                CallControlButton(
                    iconRes = R.drawable.ic_dialpad,
                    label = "Keypad",
                    active = keypadOpen,
                    onClick = { keypadOpen = !keypadOpen },
                )
            }

            // DTMF Keypad Grid (for IVR calls)
            AnimatedVisibility(
                visible = keypadOpen,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val keyRows = listOf(
                        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
                        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
                        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
                        listOf("*" to "", "0" to "+", "#" to ""),
                    )

                    for (row in keyRows) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            for ((digit, letters) in row) {
                                KeypadDigitButton(
                                    digit = digit,
                                    letters = letters,
                                    onClick = {
                                        ActiveCallManager.sendDtmf(digit.first())
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // End Call Button
            Surface(
                onClick = { ActiveCallManager.hangUp() },
                shape = CircleShape,
                color = Color(0xFFDC2626), // Red
                modifier = Modifier
                    .size(width = 84.dp, height = 48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call),
                        contentDescription = "End Call",
                        tint = Color.White,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(135f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CallControlButton(
    iconRes: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.8.sp,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeypadDigitButton(
    digit: String,
    letters: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .size(width = 84.dp, height = 50.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = digit,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (letters.isNotEmpty()) {
                Text(
                    text = letters,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
