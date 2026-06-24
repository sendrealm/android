package com.sendrealm.sdk

data class SendrealmState(
    val initialized: Boolean,
    val registered: Boolean,
    val permissionGranted: Boolean,
    val subscribed: Boolean,
    val deviceId: String?,
    val registrationToken: String?,
    val tokenStatus: String,
    val externalUserId: String?,
    val userEmail: String?,
    val platform: String,
    val sdkVersion: String
)
