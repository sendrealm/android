package com.sendrealm.sdk.network

import com.google.gson.annotations.SerializedName

data class RegisterDeviceResponse(
    val status: Int,
    val data: RegisterDeviceData
)

data class RegisterDeviceData(
    @SerializedName("app_id")
    val appId: String,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("registration_id")
    val registrationId: String,
    val platform: String,
    @SerializedName("external_user_id")
    val externalUserId: String?,
    @SerializedName("registered_at")
    val registeredAt: String
)
