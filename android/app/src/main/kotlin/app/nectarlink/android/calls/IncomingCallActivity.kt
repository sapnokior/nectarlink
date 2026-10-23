// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.R
import app.nectarlink.core.Call
import app.nectarlink.core.CallCommand
import app.nectarlink.core.CallPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full-screen incoming call UI shown over the lock screen (and when screen is off)
 * for calls received on a paired phone, similar to Google Phone.
 */
class IncomingCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeInstance = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val devId = currentDeviceId ?: intent.getStringExtra(PeerCallNotifications.EXTRA_DEVICE).orEmpty()
        val devName = currentDeviceName ?: intent.getStringExtra(PeerCallNotifications.EXTRA_DEVICE_NAME).orEmpty()
        val cId = currentCall?.id ?: intent.getStringExtra(PeerCallNotifications.EXTRA_CALL_ID).orEmpty()
        val call = currentCall

        if (call != null && !call.incoming) {
            finish()
            return
        }

        setContent {
            IncomingCallScreen(
                deviceName = devName.ifBlank { "Paired Phone" },
                call = call,
                onAnswer = {
                    val app = applicationContext as NectarlinkApplication
                    PeerCallNotifications.optimisticUpdate(devId, answered = true)
                    CoroutineScope(Dispatchers.IO).launch {
                        app.core.peerCallCommand(devId, cId, CallCommand.Answer)
                    }
                    finish()
                },
                onDecline = {
                    val app = applicationContext as NectarlinkApplication
                    currentCall?.let {
                        val endedCall = it.copy(state = CallPhase.ENDED)
                        PeerCallNotifications.broadcastUdp(devName, endedCall)
                    }
                    CoroutineScope(Dispatchers.IO).launch {
                        app.core.peerCallCommand(devId, cId, CallCommand.Decline)
                    }
                    app.core.peerCallNotifications.cancelCall(devId, cId)
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var activeInstance: IncomingCallActivity? = null

        @Volatile
        var currentCall: Call? = null
        @Volatile
        var currentDeviceId: String? = null
        @Volatile
        var currentDeviceName: String? = null

        fun updateCall(deviceId: String, deviceName: String, call: Call) {
            currentDeviceId = deviceId
            currentDeviceName = deviceName
            currentCall = call
            if (call.state != CallPhase.RINGING || !call.incoming) {
                dismissIfActive(call.id)
            }
        }

        fun dismissIfActive(callId: String? = null) {
            val instance = activeInstance ?: return
            if (callId == null || currentCall?.id == callId) {
                instance.runOnUiThread {
                    instance.finish()
                }
            }
        }
    }
}

@Composable
private fun IncomingCallScreen(
    deviceName: String,
    call: Call?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    val callerName = call?.name?.takeIf(String::isNotBlank)
        ?: call?.number?.takeIf(String::isNotBlank)
        ?: "Incoming Call"
    val callerNumber = call?.number?.takeIf { !call.name.isNullOrBlank() && it.isNotBlank() }

    val photoBitmap = remember(call?.photo) {
        call?.photo?.let { bytes ->
            if (bytes.isNotEmpty()) {
                runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            } else null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A)), // Midnight dark background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top Section: Paired device badge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color(0x3338BDF8))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call),
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Call on $deviceName",
                        color = Color(0xFFF1F5F9),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Middle Section: Caller Avatar & Info
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (photoBitmap != null) {
                    Image(
                        bitmap = photoBitmap,
                        contentDescription = callerName,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(130.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E293B)),
                        contentAlignment = Alignment.Center,
                    ) {
                        val initial = callerName.firstOrNull()?.uppercaseChar()
                        if (initial != null && initial.isLetterOrDigit()) {
                            Text(
                                text = initial.toString(),
                                color = Color(0xFF94A3B8),
                                fontSize = 54.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(54.dp),
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = callerName,
                    color = Color.White,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (callerNumber != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = callerNumber,
                        color = Color(0xFF94A3B8),
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Incoming phone call",
                    color = Color(0xFF64748B),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }

            // Bottom Section: Answer & Decline Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 56.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decline Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFDC2626))
                            .clickable(onClick = onDecline),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call),
                            contentDescription = "Decline",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Decline",
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                // Answer Button
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF16A34A))
                            .clickable(onClick = onAnswer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_call),
                            contentDescription = "Answer",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Answer",
                        color = Color(0xFFCBD5E1),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
