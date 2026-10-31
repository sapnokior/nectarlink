// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.nectarlink.android.R
import app.nectarlink.android.ui.MainActivity
import app.nectarlink.core.Call
import app.nectarlink.core.CallPhase
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles incoming calls and calls in progress from paired phones:
 * displays a unified notification with live call timer (chronometer) and interactive
 * Mute/Unmute, End, and Speaker/Bluetooth/Earpiece action keys, launches full-screen call UI
 * over lock screen, and notifies the local PC via UDP broadcast for Windows 11 toast notifications.
 */
class PeerCallNotifications(private val context: Context) {
    private val contextApp = context.applicationContext
    private val manager: NotificationManager? =
        this.contextApp.getSystemService(NotificationManager::class.java)

    /** Tracks active call IDs by device ID. */
    private val activeCallsByDevice = ConcurrentHashMap<String, MutableSet<String>>()

    /** Last known call object per device. */
    private val lastCallByDevice = ConcurrentHashMap<String, Call>()
    private val lastDeviceNameByDevice = ConcurrentHashMap<String, String>()

    /** Optimistic state overrides for immediate UI reaction. */
    private val optimisticMuted = ConcurrentHashMap<String, Boolean>()
    private val optimisticAudioRoute = ConcurrentHashMap<String, String>()
    private val optimisticAnswerTime = ConcurrentHashMap<String, Long>()

    init {
        instance = this
        createChannels()
    }

    private fun createChannels() {
        val nm = manager ?: return

        // Delete legacy channels so active calls never inherit old ringtone
        runCatching {
            nm.deleteNotificationChannel("channel_peer_calls")
            nm.deleteNotificationChannel("channel_peer_incoming_calls_v2")
            nm.deleteNotificationChannel("channel_peer_active_calls_v2")
        }

        // 1. Incoming calls channel: High importance, plays full ringtone
        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val incomingChannel = NotificationChannel(
            CHANNEL_INCOMING,
            contextApp.getString(R.string.channel_peer_calls) + " (Incoming)",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming call alerts from paired phones"
            enableVibration(true)
            setSound(
                ringtoneUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
        }

        // 2. Ongoing / Active / Outgoing calls channel: Low importance, 100% SILENT (no ringtone, no chime, no vibration)
        val activeChannel = NotificationChannel(
            CHANNEL_ACTIVE,
            "Calls in Progress & Outgoing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Silent ongoing calls and outgoing dialing controls"
            enableVibration(false)
            setSound(null, null)
        }

        // 3. Missed calls channel: Default importance
        val missedChannel = NotificationChannel(
            CHANNEL_MISSED,
            "Missed Calls",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Missed call notifications from paired phones"
            enableVibration(true)
        }

        nm.createNotificationChannels(listOf(incomingChannel, activeChannel, missedChannel))
    }

    fun onCallChanged(deviceId: String, deviceName: String, call: Call) {
        val effectiveName = deviceName.ifBlank { "Paired Phone" }
        lastCallByDevice[deviceId] = call
        lastDeviceNameByDevice[deviceId] = effectiveName

        // Always broadcast to PC over UDP
        broadcastUdp(effectiveName, call)

        if (!canPostNotifications()) return

        when (call.state) {
            CallPhase.RINGING -> {
                optimisticAnswerTime.remove(deviceId)
                if (call.incoming) {
                    showRinging(deviceId, effectiveName, call)
                } else {
                    showActive(deviceId, effectiveName, call)
                }
            }
            CallPhase.ACTIVE -> {
                // Cancel ringing alert immediately so any incoming ringtone stops instantly
                manager?.cancel(notificationId(deviceId))

                // Clear optimistic overrides when remote confirms actual state
                val sinceTime = call.since
                if (sinceTime != null) {
                    optimisticAnswerTime[deviceId] = sinceTime
                } else if (!optimisticAnswerTime.containsKey(deviceId)) {
                    optimisticAnswerTime[deviceId] = System.currentTimeMillis()
                }
                call.controls?.let { ctrl ->
                    optimisticMuted[deviceId] = ctrl.muted
                    optimisticAudioRoute[deviceId] = when {
                        ctrl.bluetooth -> "bluetooth"
                        ctrl.speaker -> "speaker"
                        else -> "earpiece"
                    }
                }
                ActiveCallManager.updatePeerCall(
                    deviceId,
                    effectiveName,
                    call,
                    optimisticMuted[deviceId],
                    optimisticAudioRoute[deviceId],
                )
                showActive(deviceId, effectiveName, call)
            }
            CallPhase.ENDED -> {
                ActiveCallManager.clearCall(call.id)
                cancelCall(deviceId, call.id)
                if (call.missed) {
                    showMissed(deviceId, effectiveName, call)
                }
            }
        }
    }

    private fun showRinging(deviceId: String, deviceName: String, call: Call) {
        val nm = manager ?: return
        val calls = activeCallsByDevice.getOrPut(deviceId) { ConcurrentHashMap.newKeySet() }
        calls.add(call.id)

        val caller = call.name?.takeIf(String::isNotBlank)
            ?: call.number?.takeIf(String::isNotBlank)
            ?: contextApp.getString(R.string.incoming_call_title)

        val body = if (!call.name.isNullOrBlank() && !call.number.isNullOrBlank()) {
            contextApp.getString(R.string.incoming_call_from, call.number, deviceName)
        } else {
            contextApp.getString(R.string.incoming_call_from, caller, deviceName)
        }

        // Update active call reference for full-screen incoming call UI
        IncomingCallActivity.updateCall(deviceId, deviceName, call)

        val fullScreenIntent = PendingIntent.getActivity(
            contextApp,
            notificationId(deviceId),
            Intent(contextApp, IncomingCallActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_CALL_ID, call.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val answerIntent = PendingIntent.getBroadcast(
            contextApp,
            actionNotificationId(deviceId, ACTION_ANSWER),
            Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                action = ACTION_ANSWER
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_CALL_ID, call.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val declineIntent = PendingIntent.getBroadcast(
            contextApp,
            actionNotificationId(deviceId, ACTION_DECLINE),
            Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_CALL_ID, call.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(contextApp, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(caller)
            .setContentText(body)
            .setSubText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenIntent)
            .setFullScreenIntent(fullScreenIntent, true)
            .addAction(R.drawable.ic_call, contextApp.getString(R.string.action_answer), answerIntent)
            .addAction(R.drawable.ic_call, contextApp.getString(R.string.action_decline), declineIntent)

        call.photo?.let { bytes ->
            if (bytes.isNotEmpty()) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let {
                    builder.setLargeIcon(it)
                }
            }
        }

        val notification = builder.build()
        notification.flags = notification.flags or android.app.Notification.FLAG_INSISTENT
        nm.notify(notificationId(deviceId), notification)
    }

    private fun showActive(deviceId: String, deviceName: String, call: Call) {
        val nm = manager ?: return
        val calls = activeCallsByDevice.getOrPut(deviceId) { ConcurrentHashMap.newKeySet() }
        calls.add(call.id)

        val caller = call.name?.takeIf(String::isNotBlank)
            ?: call.number?.takeIf(String::isNotBlank)
            ?: if (!call.incoming) "Outgoing call" else contextApp.getString(R.string.active_call_title)

        val callStartTime = call.since ?: optimisticAnswerTime[deviceId]

        val contentText = if (callStartTime == null) {
            if (!call.incoming) "Calling from $deviceName..." else "Incoming call from $deviceName"
        } else if (!call.name.isNullOrBlank() && !call.number.isNullOrBlank()) {
            call.number
        } else {
            contextApp.getString(R.string.active_call_title)
        }

        val tapIntent = PendingIntent.getActivity(
            contextApp,
            notificationId(deviceId),
            Intent(contextApp, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("select_tab", "calls")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Mute state
        val isMuted = optimisticMuted[deviceId] ?: (call.controls?.muted == true)

        // Audio route state
        val hasBluetooth = call.controls?.hasBluetooth == true ||
            call.controls?.bluetooth == true ||
            optimisticAudioRoute[deviceId] == "bluetooth"
        val currentRoute = optimisticAudioRoute[deviceId] ?: when {
            call.controls?.bluetooth == true -> "bluetooth"
            call.controls?.speaker == true -> "speaker"
            else -> "earpiece"
        }

        // 1. Mute Action: Tapping "Mute" turns to "Unmute", tapping "Unmute" turns to "Mute"
        val muteIntent = PendingIntent.getBroadcast(
            contextApp,
            actionNotificationId(deviceId, ACTION_MUTE),
            Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                action = ACTION_MUTE
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_CALL_ID, call.id)
                putExtra(EXTRA_ON, !isMuted)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 2. End Call Action
        val endIntent = PendingIntent.getBroadcast(
            contextApp,
            actionNotificationId(deviceId, ACTION_DECLINE),
            Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                action = ACTION_DECLINE
                putExtra(EXTRA_DEVICE, deviceId)
                putExtra(EXTRA_CALL_ID, call.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 3. Audio Route Action:
        // If Bluetooth is connected:
        //   - when on speaker: button is "Bluetooth", tapping switches to Bluetooth
        //   - when on earpiece or bluetooth: button is "Speaker", tapping switches to Speaker
        // If Bluetooth is NOT connected:
        //   - when on speaker: button is "Earpiece", tapping switches to Earpiece
        //   - when on earpiece: button is "Speaker", tapping switches to Speaker
        val (routeTitle, routeIcon, routeIntent) = if (hasBluetooth) {
            when (currentRoute) {
                "speaker" -> Triple(
                    "Bluetooth",
                    R.drawable.ic_bluetooth,
                    PendingIntent.getBroadcast(
                        contextApp,
                        actionNotificationId(deviceId, ACTION_BLUETOOTH),
                        Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                            action = ACTION_BLUETOOTH
                            putExtra(EXTRA_DEVICE, deviceId)
                            putExtra(EXTRA_CALL_ID, call.id)
                            putExtra(EXTRA_ON, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                "bluetooth" -> Triple(
                    "Speaker",
                    R.drawable.ic_speaker,
                    PendingIntent.getBroadcast(
                        contextApp,
                        actionNotificationId(deviceId, ACTION_SPEAKER),
                        Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                            action = ACTION_SPEAKER
                            putExtra(EXTRA_DEVICE, deviceId)
                            putExtra(EXTRA_CALL_ID, call.id)
                            putExtra(EXTRA_ON, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                else -> Triple(
                    "Speaker",
                    R.drawable.ic_speaker,
                    PendingIntent.getBroadcast(
                        contextApp,
                        actionNotificationId(deviceId, ACTION_SPEAKER),
                        Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                            action = ACTION_SPEAKER
                            putExtra(EXTRA_DEVICE, deviceId)
                            putExtra(EXTRA_CALL_ID, call.id)
                            putExtra(EXTRA_ON, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        } else {
            if (currentRoute == "speaker") {
                Triple(
                    "Earpiece",
                    R.drawable.ic_earpiece,
                    PendingIntent.getBroadcast(
                        contextApp,
                        actionNotificationId(deviceId, ACTION_SPEAKER),
                        Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                            action = ACTION_SPEAKER
                            putExtra(EXTRA_DEVICE, deviceId)
                            putExtra(EXTRA_CALL_ID, call.id)
                            putExtra(EXTRA_ON, false)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            } else {
                Triple(
                    "Speaker",
                    R.drawable.ic_speaker,
                    PendingIntent.getBroadcast(
                        contextApp,
                        actionNotificationId(deviceId, ACTION_SPEAKER),
                        Intent(contextApp, PeerCallActionReceiver::class.java).apply {
                            action = ACTION_SPEAKER
                            putExtra(EXTRA_DEVICE, deviceId)
                            putExtra(EXTRA_CALL_ID, call.id)
                            putExtra(EXTRA_ON, true)
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
            }
        }

        val builder = NotificationCompat.Builder(contextApp, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(caller)
            .setContentText(contentText)
            .setSubText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(tapIntent)

        if (callStartTime != null) {
            builder.setUsesChronometer(true)
                .setChronometerCountDown(false)
                .setWhen(callStartTime)
                .setShowWhen(true)
        } else {
            builder.setUsesChronometer(false)
                .setShowWhen(false)
        }
            .addAction(
                if (isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic,
                if (isMuted) "Unmute" else "Mute",
                muteIntent,
            )
            .addAction(
                R.drawable.ic_call,
                "End",
                endIntent,
            )
            .addAction(
                routeIcon,
                routeTitle,
                routeIntent,
            )

        call.photo?.let { bytes ->
            if (bytes.isNotEmpty()) {
                runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()?.let {
                    builder.setLargeIcon(it)
                }
            }
        }

        nm.notify(notificationId(deviceId), builder.build())
    }

    private fun showMissed(deviceId: String, deviceName: String, call: Call) {
        val nm = manager ?: return
        val caller = call.name?.takeIf(String::isNotBlank)
            ?: call.number?.takeIf(String::isNotBlank)
            ?: contextApp.getString(R.string.incoming_call_title)

        val title = contextApp.getString(R.string.missed_call_title, caller)
        val body = contextApp.getString(R.string.missed_call_body, deviceName)

        val tapIntent = PendingIntent.getActivity(
            contextApp,
            notificationId(deviceId),
            Intent(contextApp, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(contextApp, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(deviceName)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)

        nm.notify(notificationId(deviceId), builder.build())
    }

    fun notifyDecline(deviceId: String, callId: String) {
        lastCallByDevice[deviceId]?.let { call ->
            val name = lastDeviceNameByDevice[deviceId].orEmpty()
            broadcastUdp(name, call.copy(state = CallPhase.ENDED))
        }
        cancelCall(deviceId, callId)
    }

    fun cancelCall(deviceId: String, callId: String) {
        ActiveCallManager.clearCall(callId)
        activeCallsByDevice[deviceId]?.remove(callId)
        optimisticMuted.remove(deviceId)
        optimisticAudioRoute.remove(deviceId)
        optimisticAnswerTime.remove(deviceId)
        lastCallByDevice.remove(deviceId)
        lastDeviceNameByDevice.remove(deviceId)
        IncomingCallActivity.dismissIfActive(callId)
        manager?.cancel(notificationId(deviceId))
    }

    fun onDeviceOffline(deviceId: String) {
        ActiveCallManager.clearCall()
        activeCallsByDevice.remove(deviceId)
        optimisticMuted.remove(deviceId)
        optimisticAudioRoute.remove(deviceId)
        optimisticAnswerTime.remove(deviceId)
        lastCallByDevice.remove(deviceId)
        lastDeviceNameByDevice.remove(deviceId)
        IncomingCallActivity.dismissIfActive()
        manager?.cancel(notificationId(deviceId))
    }

    private fun notificationId(deviceId: String): Int =
        ("peer_call:" + deviceId).hashCode()

    private fun actionNotificationId(deviceId: String, action: String): Int =
        ("peer_call_action:" + deviceId + ":" + action).hashCode()

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            contextApp,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val CHANNEL_INCOMING = "channel_peer_incoming_v3"
        const val CHANNEL_ACTIVE = "channel_peer_active_v3"
        const val CHANNEL_MISSED = "channel_peer_missed_v3"
        const val EXTRA_DEVICE = "device_id"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_ON = "on"
        const val ACTION_ANSWER = "app.nectarlink.android.calls.ACTION_ANSWER"
        const val ACTION_DECLINE = "app.nectarlink.android.calls.ACTION_DECLINE"
        const val ACTION_MUTE = "app.nectarlink.android.calls.ACTION_MUTE"
        const val ACTION_SPEAKER = "app.nectarlink.android.calls.ACTION_SPEAKER"
        const val ACTION_BLUETOOTH = "app.nectarlink.android.calls.ACTION_BLUETOOTH"
        const val UDP_PORT = 59999

        @Volatile
        var instance: PeerCallNotifications? = null

        fun optimisticUpdate(
            deviceId: String,
            muted: Boolean? = null,
            route: String? = null,
            answered: Boolean? = null,
        ) {
            val notif = instance ?: return
            muted?.let { notif.optimisticMuted[deviceId] = it }
            route?.let { notif.optimisticAudioRoute[deviceId] = it }
            if (answered == true) {
                if (!notif.optimisticAnswerTime.containsKey(deviceId)) {
                    notif.optimisticAnswerTime[deviceId] = System.currentTimeMillis()
                }
                notif.manager?.cancel(notif.notificationId(deviceId))
            }
            val last = notif.lastCallByDevice[deviceId] ?: return
            val name = notif.lastDeviceNameByDevice[deviceId].orEmpty()
            val effectiveCall = if (answered == true) {
                val activeCall = last.copy(state = CallPhase.ACTIVE, since = notif.optimisticAnswerTime[deviceId])
                broadcastUdp(name, activeCall)
                activeCall
            } else last
            ActiveCallManager.updatePeerCall(deviceId, name, effectiveCall, muted, route)
            notif.showActive(deviceId, name, last)
        }

        /** Broadcasts call event over UDP port 59999 to local network for PC win11toast. */
        fun broadcastUdp(deviceName: String, call: Call) {
            Thread {
                runCatching {
                    val json = JSONObject().apply {
                        put("device", deviceName)
                        put("call_id", call.id)
                        put("state", when (call.state) {
                            CallPhase.RINGING -> "ringing"
                            CallPhase.ACTIVE -> "active"
                            CallPhase.ENDED -> "ended"
                        })
                        put("incoming", call.incoming)
                        put("caller", call.name ?: "")
                        put("number", call.number ?: "")
                        put("missed", call.missed)
                    }.toString()
                    val data = json.toByteArray(Charsets.UTF_8)
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        val packet = DatagramPacket(data, data.size, broadcastAddr, UDP_PORT)
                        socket.send(packet)
                    }
                }.onFailure { Log.d("PeerCallNotifications", "UDP broadcast error", it) }
            }.start()
        }
    }
}
