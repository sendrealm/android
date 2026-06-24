package com.sendrealm.sdk

import com.google.gson.annotations.SerializedName

data class SendrealmPayload(
    val notification: SendrealmNotification? = null,
    val metadata: SendrealmMetadata? = null,
    val data: Map<String, Any?>? = null,
    @SerializedName(value = "live_activity", alternate = ["liveActivity"])
    val liveActivity: SendrealmLiveActivity? = null,
    val actions: List<SendrealmNotificationAction>? = null
)

data class SendrealmNotification(
    val title: String,
    val body: String,
    val android: AndroidNotificationSettings? = null,
    val ios: IosNotificationSettings? = null
)

data class SendrealmMetadata(
    @SerializedName(value = "notification_id", alternate = ["notificationId"])
    val notificationId: String? = null,
    @SerializedName(value = "delivery_id", alternate = ["deliveryId"])
    val deliveryId: String? = null,
    @SerializedName(value = "click_id", alternate = ["clickId"])
    val clickId: String? = null,
    @SerializedName(value = "android_launch_url", alternate = ["androidLaunchUrl"])
    val androidLaunchUrl: String? = null,
    @SerializedName(value = "ios_launch_url", alternate = ["iosLaunchUrl"])
    val iosLaunchUrl: String? = null,
    @SerializedName(value = "launch_url", alternate = ["launchUrl"])
    val launchUrl: String? = null,
    @SerializedName(value = "image_url", alternate = ["imageUrl"])
    val imageUrl: String? = null
)

data class SendrealmNotificationAction(
    val id: String,
    val title: String? = null,
    val text: String? = null,
    val icon: String? = null,
    @SerializedName(value = "launch_url", alternate = ["launchUrl"])
    val launchUrl: String? = null
) {
    val displayTitle: String
        get() = (title ?: text).orEmpty()
}

data class SendrealmLiveActivity(
    @SerializedName(value = "activity_id", alternate = ["activityId"])
    val activityId: String,
    @SerializedName(value = "send_id", alternate = ["sendId"])
    val sendId: String? = null,
    @SerializedName(value = "activity_type", alternate = ["activityType"])
    val activityType: String? = null,
    @SerializedName(value = "attributes_type", alternate = ["attributesType"])
    val attributesType: String? = null,
    val event: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val body: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val eta: String? = null,
    @SerializedName(value = "image_url", alternate = ["imageUrl"])
    val imageUrl: String? = null,
    @SerializedName(value = "accent_color", alternate = ["accentColor"])
    val accentColor: String? = null,
    @SerializedName(value = "launch_url", alternate = ["launchUrl"])
    val launchUrl: String? = null,
    @SerializedName(value = "content_state", alternate = ["contentState"])
    val contentState: Map<String, Any?>? = null,
    val attributes: Map<String, Any?>? = null,
    val priority: Double? = null,
    @SerializedName(value = "stale_date", alternate = ["staleDate"])
    val staleDate: String? = null,
    @SerializedName(value = "dismissal_date", alternate = ["dismissalDate"])
    val dismissalDate: String? = null,
    @SerializedName(value = "relevance_score", alternate = ["relevanceScore"])
    val relevanceScore: Double? = null,
    val sound: String? = null,
    val buttons: List<SendrealmNotificationAction>? = null
)

data class AndroidNotificationSettings(
    @SerializedName("channelId")
    val channelId: String? = null,
    @SerializedName("channelName")
    val channelName: String? = null,
    @SerializedName("channelDescription")
    val channelDescription: String? = null,
    @SerializedName("smallIcon")
    val smallIcon: String? = null,
    val largeIcon: String? = null,
    val bigPicture: String? = null,
    val color: String? = null,
    val priority: String? = null,
    val importance: String? = null,
    val sound: String? = null,
    val vibration: Boolean? = null,
    val ledColor: String? = null,
    val lockscreenVisibility: String? = null
)

data class IosNotificationSettings(
    val badge: Int? = null,
    val sound: String? = null
)

class SendrealmForegroundNotificationEvent(
    val payload: SendrealmPayload,
    val rawPayload: String,
    val isForeground: Boolean
) {
    val notificationId: String?
        get() = payload.metadata?.notificationId

    val launchUrl: String?
        get() = payload.metadata?.androidLaunchUrl

    var shouldDisplay: Boolean = true
        private set

    fun preventDefault() {
        shouldDisplay = false
    }
}

fun interface SendrealmNotificationClickListener {
    fun onClick(event: SendrealmNotificationOpenResult)
}

fun interface SendrealmPermissionObserver {
    fun onPermissionChanged(granted: Boolean)
}

fun interface SendrealmSubscriptionObserver {
    fun onSubscriptionChanged(subscribed: Boolean)
}

fun interface SendrealmForegroundNotificationListener {
    fun onWillDisplay(event: SendrealmForegroundNotificationEvent)
}

class SendrealmSilentNotificationEvent(
    val payload: SendrealmPayload,
    val rawPayload: String,
    val isForeground: Boolean
) {
    val notificationId: String?
        get() = payload.metadata?.notificationId
}

class SendrealmNotificationActionEvent(
    val notificationId: String?,
    val actionIdentifier: String?,
    val launchUrl: String?,
    val rawPayload: String?,
    val payload: SendrealmPayload?
)

fun interface SendrealmSilentNotificationListener {
    fun onSilentNotification(event: SendrealmSilentNotificationEvent)
}

fun interface SendrealmNotificationActionListener {
    fun onAction(event: SendrealmNotificationActionEvent)
}
