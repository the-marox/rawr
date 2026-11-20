package com.rawr.camera.video

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 17U: optional Do Not Disturb while recording, so notification banners don't
 * cost frames. Uses the user's own DND (priority) rules and restores the
 * previous mode when recording ends, unless the user changed it meanwhile.
 * The state survives process death: a stale "applied" flag is undone on the
 * next idle viewfinder.
 */
object RecordingDnd {
    private const val PREFS = "rawr_17u"
    private const val KEY_ENABLED = "dnd_while_recording"
    private const val KEY_APPLIED = "dnd_applied"
    private const val KEY_PREVIOUS = "dnd_previous_filter"
    private const val FILTER = NotificationManager.INTERRUPTION_FILTER_PRIORITY

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun manager(context: Context) =
        context.getSystemService(NotificationManager::class.java)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun accessGranted(context: Context): Boolean =
        manager(context)?.isNotificationPolicyAccessGranted == true

    fun requestAccess(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun begin(context: Context) {
        if (!enabled(context) || !accessGranted(context)) return
        val nm = manager(context) ?: return
        val p = prefs(context)
        if (p.getBoolean(KEY_APPLIED, false)) return
        val current = nm.currentInterruptionFilter
        // Already silent (user DND on): leave it alone.
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return
        runCatching { nm.setInterruptionFilter(FILTER) }.onSuccess {
            p.edit().putBoolean(KEY_APPLIED, true).putInt(KEY_PREVIOUS, current).apply()
        }
    }

    fun end(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(KEY_APPLIED, false)) return
        val nm = manager(context)
        if (nm != null && accessGranted(context) && nm.currentInterruptionFilter == FILTER) {
            runCatching {
                nm.setInterruptionFilter(p.getInt(KEY_PREVIOUS, NotificationManager.INTERRUPTION_FILTER_ALL))
            }
        }
        p.edit().putBoolean(KEY_APPLIED, false).apply()
    }
}
