// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.content.Context
import android.util.Log
import app.nectarlink.android.NectarlinkApplication
import app.nectarlink.core.Call
import app.nectarlink.core.CallCommand
import app.nectarlink.core.CallPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveCallState(
    val deviceId: String?, // null if local, else device id of peer phone
    val deviceName: String?,
    val callId: String,
    val number: String,
    val name: String?,
    val photo: ByteArray? = null,
    val since: Long, // timestamp ms
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val isHeld: Boolean = false,
    val canHold: Boolean = true,
    val isLocal: Boolean = false,
    val pressedDigits: String = "",
)

object ActiveCallManager {
    private const val TAG = "ActiveCallManager"
    private val _activeCall = MutableStateFlow<ActiveCallState?>(null)
    val activeCall = _activeCall.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun startOutgoing(
        deviceId: String?,
        deviceName: String?,
        number: String,
        name: String?,
    ) {
        _activeCall.value = ActiveCallState(
            deviceId = deviceId,
            deviceName = deviceName,
            callId = "outgoing_${System.currentTimeMillis()}",
            number = number,
            name = name,
            since = System.currentTimeMillis(),
            isLocal = deviceId == null,
        )
    }

    fun updateLocalCall(
        callId: String,
        number: String?,
        name: String?,
        since: Long,
        isMuted: Boolean = false,
        isSpeaker: Boolean = false,
        isHeld: Boolean = false,
        canHold: Boolean = true,
    ) {
        val current = _activeCall.value
        val digits = if (current != null && current.callId == callId) current.pressedDigits else ""
        _activeCall.value = ActiveCallState(
            deviceId = null,
            deviceName = null,
            callId = callId,
            number = number.orEmpty(),
            name = name,
            since = since,
            isMuted = isMuted,
            isSpeaker = isSpeaker,
            isHeld = isHeld,
            canHold = canHold,
            isLocal = true,
            pressedDigits = digits,
        )
    }

    fun updatePeerCall(
        deviceId: String,
        deviceName: String,
        call: Call,
        optimisticMuted: Boolean? = null,
        optimisticRoute: String? = null,
    ) {
        val current = _activeCall.value
        val matchesCurrent = current != null && (
            current.callId == call.id ||
            (current.deviceId == deviceId && (current.callId.startsWith("outgoing_") || current.number == call.number))
        )

        if (call.state != CallPhase.ACTIVE) {
            if (matchesCurrent) {
                _activeCall.value = null
            }
            return
        }
        val digits = if (matchesCurrent) current!!.pressedDigits else ""
        val muted = optimisticMuted ?: (call.controls?.muted == true)
        val speaker = if (optimisticRoute != null) optimisticRoute == "speaker" else (call.controls?.speaker == true)
        val held = call.controls?.held == true
        val canHold = call.controls?.canHold ?: true
        val sinceTime = call.since ?: current?.since ?: System.currentTimeMillis()

        _activeCall.value = ActiveCallState(
            deviceId = deviceId,
            deviceName = deviceName,
            callId = call.id,
            number = call.number.orEmpty().ifBlank { current?.number.orEmpty() },
            name = call.name ?: current?.name,
            photo = call.photo ?: current?.photo,
            since = sinceTime,
            isMuted = muted,
            isSpeaker = speaker,
            isHeld = held,
            canHold = canHold,
            isLocal = false,
            pressedDigits = digits,
        )
    }

    fun clearCall(callId: String? = null) {
        val current = _activeCall.value ?: return
        if (callId == null || current.callId == callId) {
            _activeCall.value = null
        }
    }

    fun sendDtmf(digit: Char): Boolean {
        val call = _activeCall.value ?: return false
        val newDigits = (call.pressedDigits + digit).takeLast(24)
        _activeCall.value = call.copy(pressedDigits = newDigits)

        return if (call.isLocal) {
            CallCompanion.dtmf(digit)
        } else {
            val devId = call.deviceId ?: return false
            val app = appContext as? NectarlinkApplication
            if (app != null) {
                app.core.peerCallCommand(devId, call.callId, CallCommand.Dtmf(digit.toString()))
                true
            } else false
        }
    }

    fun clearDtmf() {
        val call = _activeCall.value ?: return
        _activeCall.value = call.copy(pressedDigits = "")
    }

    fun backspaceDtmf() {
        val call = _activeCall.value ?: return
        if (call.pressedDigits.isNotEmpty()) {
            _activeCall.value = call.copy(pressedDigits = call.pressedDigits.dropLast(1))
        }
    }

    fun setMute(on: Boolean) {
        val call = _activeCall.value ?: return
        _activeCall.value = call.copy(isMuted = on)
        if (call.isLocal) {
            CallCompanion.mute(on)
        } else {
            val devId = call.deviceId ?: return
            PeerCallNotifications.optimisticUpdate(devId, muted = on)
            (appContext as? NectarlinkApplication)?.core?.peerCallCommand(devId, call.callId, CallCommand.Mute(on))
        }
    }

    fun setSpeaker(on: Boolean) {
        val call = _activeCall.value ?: return
        _activeCall.value = call.copy(isSpeaker = on)
        if (call.isLocal) {
            CallCompanion.speaker(on)
        } else {
            val devId = call.deviceId ?: return
            val route = if (on) "speaker" else "earpiece"
            PeerCallNotifications.optimisticUpdate(devId, route = route)
            (appContext as? NectarlinkApplication)?.core?.peerCallCommand(devId, call.callId, CallCommand.Speaker(on))
        }
    }

    fun setHold(on: Boolean) {
        val call = _activeCall.value ?: return
        _activeCall.value = call.copy(isHeld = on)
        if (call.isLocal) {
            CallCompanion.hold(on)
        } else {
            val devId = call.deviceId ?: return
            (appContext as? NectarlinkApplication)?.core?.peerCallCommand(devId, call.callId, CallCommand.Hold(on))
        }
    }

    fun hangUp() {
        val call = _activeCall.value ?: return
        _activeCall.value = null
        if (call.isLocal) {
            CallCompanion.hangUp()
        } else {
            val devId = call.deviceId ?: return
            (appContext as? NectarlinkApplication)?.let { app ->
                PeerCallNotifications.instance?.notifyDecline(devId, call.callId)
                app.core.peerCallCommand(devId, call.callId, CallCommand.Decline)
                PeerCallNotifications.instance?.cancelCall(devId, call.callId)
            }
        }
    }
}
