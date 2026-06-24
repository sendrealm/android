package com.sendrealm.sdk.network

import com.google.gson.annotations.SerializedName

data class NotificationChannelsSyncResponse(
    val status: Int,
    val data: NotificationChannelsSyncData
)

data class NotificationChannelsSyncData(
    @SerializedName("app_id")
    val appId: String,
    val platform: String,
    @SerializedName("synced_at")
    val syncedAt: String,
    val channels: List<RemoteNotificationChannel>
)

data class RemoteNotificationChannel(
    @SerializedName("channel_id")
    val channelId: String,
    val name: String,
    val description: String? = null,
    val importance: String? = null,
    val sound: String? = null,
    val vibration: Boolean? = null,
    @SerializedName("led_color")
    val ledColor: String? = null,
    @SerializedName("lockscreen_visibility")
    val lockscreenVisibility: String? = null,
    @SerializedName("is_default")
    val isDefault: Boolean = false,
    @SerializedName("updated_at")
    val updatedAt: String? = null
)
