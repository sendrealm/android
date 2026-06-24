package com.sendrealm.sdk

data class SendrealmNotificationOpenResult(
    val notificationId: String?,
    val deliveryId: String? = null,
    val clickId: String? = null,
    val actionIdentifier: String? = null,
    val launchUrl: String?,
    val rawPayload: String?,
    val payload: SendrealmPayload? = null
)
