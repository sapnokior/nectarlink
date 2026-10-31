// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.android.R
import app.nectarlink.android.core.Core
import app.nectarlink.android.core.Device
import app.nectarlink.android.ui.theme.NectarlinkTheme
import kotlinx.coroutines.launch

class CallHandlerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        super.onCreate(savedInstanceState)

        val rawText = extractRawTextFromIntent(intent)
        val phoneNumber = cleanPhoneNumber(rawText)

        if (phoneNumber.isNullOrBlank()) {
            Toast.makeText(this, "No valid phone number found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val app = application as NectarlinkApplication
        val core = app.core
        val devices = core.state.value.devices
        val connectedPhones = devices.filter { it.isAndroid && it.online }
        val connectedPcs = devices.filter { !it.isAndroid && it.online }

        if (connectedPhones.isEmpty() && connectedPcs.isEmpty()) {
            // No connected peers, dial locally immediately
            PhoneCalls.dial(this, phoneNumber)
            finish()
            return
        }

        setContent {
            NectarlinkTheme {
                CallHandlerDialog(
                    number = phoneNumber,
                    connectedPhones = connectedPhones,
                    connectedPcs = connectedPcs,
                    core = core,
                    onFinish = { finish() },
                )
            }
        }
    }

    private fun extractRawTextFromIntent(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            Intent.ACTION_DIAL, Intent.ACTION_VIEW -> {
                val data = intent.data
                if (data != null && data.scheme == "tel") {
                    data.schemeSpecificPart ?: data.toString().removePrefix("tel:")
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT) ?: data?.toString()
                }
            }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent.data?.schemeSpecificPart ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    private fun cleanPhoneNumber(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        // If it starts with tel: prefix
        val stripped = if (trimmed.startsWith("tel:", ignoreCase = true)) {
            trimmed.substring(4)
        } else {
            trimmed
        }
        val cleaned = stripped.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        return if (cleaned.length >= 3) cleaned else null
    }
}

@Composable
private fun CallHandlerDialog(
    number: String,
    connectedPhones: List<Device>,
    connectedPcs: List<Device>,
    core: Core,
    onFinish: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onFinish,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_call),
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.call_handler_title, number),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Choose which device to make this call from:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Connected Phone buttons
                connectedPhones.forEach { dev ->
                    Button(
                        onClick = {
                            scope.launch {
                                core.peerDial(dev.id, number)
                                onFinish()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2E7D32),
                            contentColor = Color.White,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_call),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.call_handler_via_peer, dev.name))
                        }
                    }
                }

                // Local phone button
                OutlinedButton(
                    onClick = {
                        PhoneCalls.dial(context, number)
                        onFinish()
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.call_handler_via_local))
                }

                // Send to PC button if PC is connected
                if (connectedPcs.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                core.sendClipboard(number)
                                Toast.makeText(context, "Phone number sent to PC", Toast.LENGTH_SHORT).show()
                                onFinish()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.call_handler_send_pc))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onFinish) {
                Text("Cancel")
            }
        },
    )
}
