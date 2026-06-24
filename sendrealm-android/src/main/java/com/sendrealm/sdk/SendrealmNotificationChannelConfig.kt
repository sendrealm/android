package com.sendrealm.sdk

import android.app.NotificationManager
import android.app.Notification

data class SendrealmNotificationChannelConfig(
    val id: String,
    val name: String,
    val description: String? = null,
    val importance: Int = NotificationManager.IMPORTANCE_HIGH,
    val soundName: String? = null,
    val enableVibration: Boolean = true,
    val vibrationPattern: LongArray? = null,
    val enableLights: Boolean = false,
    val lightColor: Int? = null,
    val lockscreenVisibility: Int = Notification.VISIBILITY_PUBLIC,
    val showBadge: Boolean = true
)

data class SendrealmNotificationChannelState(
    val id: String,
    val name: String,
    val description: String?,
    val importance: Int,
    val soundUri: String?,
    val enableVibration: Boolean,
    val enableLights: Boolean,
    val lightColor: Int,
    val lockscreenVisibility: Int,
    val showBadge: Boolean
)
