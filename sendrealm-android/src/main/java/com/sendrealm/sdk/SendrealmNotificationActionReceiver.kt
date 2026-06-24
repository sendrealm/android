package com.sendrealm.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SendrealmNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Sendrealm.ACTION_NOTIFICATION_OPEN -> {
                Sendrealm.handleNotificationAction(context, intent)
            }
            Sendrealm.ACTION_NOTIFICATION_ACTION -> {
                Sendrealm.handleNotificationAction(context, intent)
            }
            Sendrealm.ACTION_NOTIFICATION_DISMISS -> {
                Sendrealm.handleNotificationDismissed(context, intent)
            }
        }
    }
}
