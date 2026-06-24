package com.sendrealm.sdk

data class SendrealmDiagnostics(
    val appId: String?,
    val apiUrl: String,
    val apiUrlSource: String,
    val sdkVersion: String,
    val platform: String,
    val deviceId: String?,
    val registrationTokenPresent: Boolean,
    val permissionStatus: String,
    val subscribed: Boolean,
    val appVersion: String?,
    val deviceModel: String,
    val osVersion: String,
    val locale: String,
    val timezone: String,
    val lastInitResult: SendrealmOperationResult?,
    val lastRegisterResult: SendrealmOperationResult?,
    val lastSdkError: SendrealmSdkError?,
    val queueCounts: SendrealmQueueCounts,
    val lastNotificationPayload: String?,
    val lastOpenPayload: String?
)

data class SendrealmOperationResult(
    val success: Boolean,
    val message: String? = null,
    val at: Long = System.currentTimeMillis()
)

data class SendrealmSdkError(
    val code: String,
    val message: String,
    val at: Long = System.currentTimeMillis()
)

data class SendrealmQueueCounts(
    val events: Int,
    val tags: Int,
    val registrations: Int,
    val subscriptions: Int
)

data class SendrealmForegroundPresentationOptions(
    val display: Boolean = true
)
