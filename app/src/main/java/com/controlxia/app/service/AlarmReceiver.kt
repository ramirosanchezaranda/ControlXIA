package com.controlxia.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.controlxia.app.actions.OwnAlarms

/** Las alarmas propias: sonar a la hora, y "Detener" / "Posponer" de la notificación. */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_FIRE -> OwnAlarms.ring(context, intent.getIntExtra(EXTRA_ID, 0))
            ACTION_STOP -> OwnAlarms.stop(context)
            ACTION_SNOOZE -> OwnAlarms.snooze(context)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.controlxia.app.action.ALARM_FIRE"
        const val ACTION_STOP = "com.controlxia.app.action.ALARM_STOP"
        const val ACTION_SNOOZE = "com.controlxia.app.action.ALARM_SNOOZE"
        const val EXTRA_ID = "id"
    }
}
