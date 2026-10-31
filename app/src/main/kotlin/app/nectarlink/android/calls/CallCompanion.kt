// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.calls

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import app.nectarlink.core.CallControls

/**
 * NectarLink as a calling companion: Telecom binds this while there are
 * calls, without NectarLink being the phone app, so a PC can mute, switch to
 * the speaker, hold, and press keys on the call in progress. It shows
 * nothing on the phone. Telecom binds companions that hold
 * MANAGE_ONGOING_CALLS (Android 12+), an app-op Elevated's shell grants.
 */
class CallCompanion : InCallService() {
    /** The call being controlled: the active one, or the latest. */
    private var call: Call? = null

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = changed()
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        this.call = call
        changed()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        if (this.call === call) this.call = calls.lastOrNull { it !== call }
        if (this.call == null) {
            ActiveCallManager.clearCall()
        }
        changed()
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) = changed()

    /** Only a call in progress has controls; ringing and ending report themselves. */
    private fun changed() {
        val num = number()
        val nm = name()
        if (num != null || nm != null) {
            PhoneCalls.companionDetails(num, nm)
        }
        val state = call?.stateNow()
        val connectTime = connectTimeMillis()
        if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
            PhoneCalls.callAnswered(connectTime)
            PhoneCalls.controlsChanged()
            @Suppress("DEPRECATION")
            val audio = callAudioState
            ActiveCallManager.updateLocalCall(
                callId = call?.details?.handle?.toString() ?: "local_call",
                number = num,
                name = nm,
                since = connectTime ?: System.currentTimeMillis(),
                isMuted = audio?.isMuted == true,
                isSpeaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
                isHeld = state == Call.STATE_HOLDING,
                canHold = call?.details?.can(Call.Details.CAPABILITY_HOLD) == true,
            )
        } else if (state == Call.STATE_DISCONNECTED || state == null) {
            ActiveCallManager.clearCall()
        }
    }

    /** The time the call connected in milliseconds (null when not connected yet). */
    private fun connectTimeMillis(): Long? =
        call?.details?.connectTimeMillis?.takeIf { it > 0 }

    /** The call's number, from its `tel:` handle (null when hidden). */
    private fun number(): String? =
        call?.details?.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.takeIf { it.isNotBlank() }

    /** The name Telecom found for the caller (Android 11+), or caller ID, when it has one. */
    private fun name(): String? {
        val details = call?.details ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val contact = details.contactDisplayName?.trim()?.takeIf { it.isNotEmpty() }
            if (contact != null) return contact
        }
        val caller = details.callerDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        if (caller != null) return caller
        return null
    }

    private fun controls(): CallControls? {
        val call = call ?: return null
        @Suppress("DEPRECATION")
        val audio = callAudioState ?: return null
        val isMuted = audio.isMuted
        val isSpeaker = audio.route == CallAudioState.ROUTE_SPEAKER
        val isBluetooth = audio.route == CallAudioState.ROUTE_BLUETOOTH
        val hasBluetooth = (audio.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
        return CallControls(
            muted = isMuted,
            speaker = isSpeaker,
            held = call.stateNow() == Call.STATE_HOLDING,
            canHold = call.details.can(Call.Details.CAPABILITY_HOLD),
            bluetooth = isBluetooth,
            hasBluetooth = hasBluetooth,
        )
    }

    private fun answer(): Boolean {
        val call = call?.takeIf { it.stateNow() == Call.STATE_RINGING } ?: return false
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        return true
    }

    private fun hangUp(): Boolean {
        val call = call ?: return false
        if (call.stateNow() == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
        return true
    }

    @Suppress("DEPRECATION") // setAudioRoute: still how a companion picks the speaker.
    private fun speaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    @Suppress("DEPRECATION")
    private fun bluetooth(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_BLUETOOTH else CallAudioState.ROUTE_WIRED_OR_EARPIECE)
    }

    private fun hold(on: Boolean): Boolean {
        val call = call ?: return false
        if (on) call.hold() else call.unhold()
        return true
    }

    private fun dtmf(digit: Char): Boolean {
        val call = call ?: return false
        call.playDtmfTone(digit)
        mainHandler.postDelayed({
            runCatching { call.stopDtmfTone() }
        }, 160)
        return true
    }

    companion object {
        @Volatile private var instance: CallCompanion? = null

        /** Whether Telecom has bound it (there's a call, and the phone allows companions). */
        val bound: Boolean get() = instance != null

        /** Whether Telecom will bind it: Android 12+, with the app-op allowed. */
        fun allowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            return runCatching {
                // Deprecated on 37 for attribution-aware checks; this one is only the app's own.
                @Suppress("DEPRECATION")
                ops.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }

        /** The app-op's name for `appops set`. */
        const val OP_NAME = "MANAGE_ONGOING_CALLS"
        private const val OP = "android:manage_ongoing_calls"

        /** The call in progress' mute, speaker and hold, when bound. */
        fun controls(): CallControls? = instance?.controls()

        fun connectTimeMillis(): Long? = instance?.connectTimeMillis()

        fun isConnected(): Boolean {
            val c = instance?.call ?: return false
            val s = c.stateNow()
            return s == Call.STATE_ACTIVE || s == Call.STATE_HOLDING
        }

        /** The call's number as Telecom has it, when bound. */
        fun number(): String? = instance?.number()

        /** The caller's name as Telecom has it, when bound. */
        fun name(): String? = instance?.name()

        fun answer(): Boolean = instance?.answer() ?: false

        fun hangUp(): Boolean = instance?.hangUp() ?: false

        fun mute(on: Boolean): Boolean = instance?.run { setMuted(on); true } ?: false

        fun speaker(on: Boolean): Boolean = instance?.run { speaker(on); true } ?: false

        fun bluetooth(on: Boolean): Boolean = instance?.run { bluetooth(on); true } ?: false

        fun hold(on: Boolean): Boolean = instance?.hold(on) ?: false

        fun dtmf(digit: Char): Boolean = instance?.dtmf(digit) ?: false
    }
}

/** The call's state: `Details.getState` from Android 12, `getState` before. */
private fun Call.stateNow(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) details.state else @Suppress("DEPRECATION") state
