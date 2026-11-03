// SPDX-License-Identifier: GPL-3.0-or-later
package app.nectarlink.android.contacts

import android.content.Context

object ContactDefaultsPrefs {
    private const val PREFS_NAME = "nectarlink_contact_defaults"

    fun getDefaultNumber(context: Context, contactId: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(contactId, null)
    }

    fun setDefaultNumber(context: Context, contactId: String, number: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(contactId, number).apply()
    }
}
