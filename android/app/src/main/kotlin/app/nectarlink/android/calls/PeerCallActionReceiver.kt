// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.core.CallCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles notification actions (Answer, Decline, End Call, Mute, Speaker, Bluetooth) for calls on paired devices.
 */
class PeerCallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val deviceId = intent.getStringExtra(PeerCallNotifications.EXTRA_DEVICE) ?: return
        val callId = intent.getStringExtra(PeerCallNotifications.EXTRA_CALL_ID) ?: return
        val action = intent.action ?: return
        val app = context.applicationContext as NectarlinkApplication

        CoroutineScope(Dispatchers.IO).launch {
            when (action) {
                PeerCallNotifications.ACTION_ANSWER -> {
                    Log.i("PeerCallActionReceiver", "Answering call $callId on device $deviceId")
                    PeerCallNotifications.optimisticUpdate(deviceId, answered = true)
                    app.core.peerCallCommand(deviceId, callId, CallCommand.Answer)
                    IncomingCallActivity.dismissIfActive(callId)
                }
                PeerCallNotifications.ACTION_DECLINE -> {
                    Log.i("PeerCallActionReceiver", "Declining/Ending call $callId on device $deviceId")
                    PeerCallNotifications.instance?.notifyDecline(deviceId, callId)
                    app.core.peerCallCommand(deviceId, callId, CallCommand.Decline)
                    IncomingCallActivity.dismissIfActive(callId)
                }
                PeerCallNotifications.ACTION_MUTE -> {
                    val on = intent.getBooleanExtra(PeerCallNotifications.EXTRA_ON, true)
                    Log.i("PeerCallActionReceiver", "Setting mute=$on for call $callId on device $deviceId")
                    PeerCallNotifications.optimisticUpdate(deviceId, muted = on)
                    app.core.peerCallCommand(deviceId, callId, CallCommand.Mute(on = on))
                }
                PeerCallNotifications.ACTION_SPEAKER -> {
                    val on = intent.getBooleanExtra(PeerCallNotifications.EXTRA_ON, true)
                    Log.i("PeerCallActionReceiver", "Setting speaker=$on for call $callId on device $deviceId")
                    PeerCallNotifications.optimisticUpdate(deviceId, route = if (on) "speaker" else "earpiece")
                    app.core.peerCallCommand(deviceId, callId, CallCommand.Speaker(on = on))
                }
                PeerCallNotifications.ACTION_BLUETOOTH -> {
                    val on = intent.getBooleanExtra(PeerCallNotifications.EXTRA_ON, true)
                    Log.i("PeerCallActionReceiver", "Setting bluetooth=$on for call $callId on device $deviceId")
                    PeerCallNotifications.optimisticUpdate(deviceId, route = if (on) "bluetooth" else "speaker")
                    app.core.peerCallCommand(deviceId, callId, CallCommand.Bluetooth(on = on))
                }
            }
        }
    }
}
