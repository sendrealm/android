package com.sendrealm.sdk.network

import com.google.gson.annotations.SerializedName

data class InitApiResponse(
    val status: Int,
    val data: InitData
)

data class InitData(
    @SerializedName("app_id")
    val appId: String,
    @SerializedName("device_id")
    val deviceId: String,
    val platform: String,
    @SerializedName("initialized_at")
    val initializedAt: String,
    @SerializedName("fcm_app")
    val fcmApp: FcmApp
)

data class FcmApp(
    @SerializedName("application_id")
    val applicationId: String,
    @SerializedName("project_id")
    val projectId: String,
    @SerializedName("sender_id")
    val senderId: String,
    @SerializedName("api_key")
    val apiKey: String
)