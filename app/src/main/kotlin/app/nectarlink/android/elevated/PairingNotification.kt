// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.elevated

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import app.nectarlink.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where the user types wireless debugging's pairing code: Android closes
 * its pairing dialog when another app comes to the front, so the code is
 * typed into this notification's reply field instead, with the dialog
 * still open behind it.
 */
object PairingNotification {
    private const val CHANNEL = "elevated"
    private const val ID = 7
    private const val KEY_CODE = "code"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Pairing notification disabled per user request (no ADB / Wireless debugging). */
    fun show(context: Context, text: String = ""): Boolean {
        dismiss(context)
        return false
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(ID)

    /** The code the user typed. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.filter(Char::isDigit)
            val app = context.applicationContext
            if (code.isNullOrEmpty()) {
                show(app)
                return
            }
            show(app, app.getString(R.string.elevated_pairing))
            val pending = goAsync()
            scope.launch {
                try {
                    if (Elevated.pair(code)) {
                        dismiss(app)
                    } else {
                        val why = when ((Elevated.state.value as? Elevated.State.Failed)?.reason) {
                            Elevated.Reason.NoPairingDialog -> R.string.elevated_no_dialog
                            Elevated.Reason.WrongCode -> R.string.elevated_wrong_code
                            else -> R.string.elevated_failed
                        }
                        show(app, app.getString(why))
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
