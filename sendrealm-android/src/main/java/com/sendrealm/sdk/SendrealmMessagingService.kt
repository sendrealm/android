package com.sendrealm.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson

class SendrealmMessagingService : FirebaseMessagingService() {

    private val gson = Gson()
    private var testingBitmapLoader: ((Context, String) -> Bitmap?)? = null

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("Sendrealm", "Message received from: ${remoteMessage.from}")

        val sendrealmData = remoteMessage.data["sendrealm_v1"]
        if (!sendrealmData.isNullOrBlank()) {
            Log.d("Sendrealm", "Message data: ${remoteMessage.data}")
            parseSendrealmNotification(sendrealmData)
            return
        }

        remoteMessage.notification?.let {
            showNotification(
                title = it.title,
                body = it.body,
                channelId = "sendrealm_channel",
                smallIcon = null,
                largeIcon = null,
                bigPicture = null,
                colorHex = null,
                androidSettings = null,
                metadata = null,
                rawPayload = null,
                actions = null
            )
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("Sendrealm", "New FCM token: $token")
        Sendrealm.onNewToken(applicationContext, token)
    }

    private fun parseSendrealmNotification(jsonData: String) {
        try {
            val payload = gson.fromJson(jsonData, SendrealmPayload::class.java)
            val liveActivity = payload.liveActivity

            if (liveActivity != null) {
                showLiveActivity(liveActivity, payload, jsonData)
                return
            }

            val notification = payload.notification

            if (notification == null) {
                Sendrealm.onSilentNotificationReceived(applicationContext, payload, jsonData)
                Log.d("Sendrealm", "Handled silent Sendrealm notification")
                return
            }

            val foregroundEvent =
                Sendrealm.onNotificationReceived(applicationContext, payload, jsonData)

            if (foregroundEvent.isForeground && (!foregroundEvent.shouldDisplay || !Sendrealm.shouldDisplayForegroundNotification(applicationContext))) {
                Log.d("Sendrealm", "Foreground listener suppressed notification display")
                return
            }

            Log.d(
                "Sendrealm",
                "Parsed notification - Title: ${notification.title}, Body: ${notification.body}"
            )

            val channelId = notification.android?.channelId ?: "sendrealm_channel"
            val smallIcon = notification.android?.smallIcon
            val largeIcon = notification.android?.largeIcon
            val bigPicture = notification.android?.bigPicture ?: payload.metadata?.imageUrl

            showNotification(
                title = notification.title,
                body = notification.body,
                channelId = channelId,
                smallIcon = smallIcon,
                largeIcon = largeIcon,
                bigPicture = bigPicture,
                colorHex = notification.android?.color,
                androidSettings = notification.android,
                metadata = payload.metadata,
                rawPayload = jsonData,
                actions = payload.actions
            )
        } catch (e: Exception) {
            Log.e("Sendrealm", "Failed to parse sendrealm notification", e)
        }
    }

    private fun loadBitmapFromUrl(context: Context, url: String): Bitmap? {
        testingBitmapLoader?.let { return it(context, url) }
        return try {
            Glide.with(context)
                .asBitmap()
                .load(url)
                .submit()
                .get()
        } catch (_: Exception) {
            null
        }
    }

    private fun showNotification(
        title: String?,
        body: String?,
        channelId: String,
        smallIcon: String?,
        largeIcon: String?,
        bigPicture: String?,
        colorHex: String?,
        androidSettings: AndroidNotificationSettings?,
        metadata: SendrealmMetadata?,
        rawPayload: String?,
        actions: List<SendrealmNotificationAction>?
    ) {
        createNotificationChannel(channelId, androidSettings)

        val notificationRequestCode =
            metadata?.notificationId?.hashCode() ?: System.currentTimeMillis().toInt()
        val pendingIntent = createContentIntent(notificationRequestCode, metadata, rawPayload)
        val deleteIntent = createDeleteIntent(notificationRequestCode, metadata)
        val iconRes = getIconResource(smallIcon)
        val largeIconBitmap = largeIcon?.let { loadBitmapFromUrl(this, it) }
        val bigPictureBitmap = bigPicture?.let { loadBitmapFromUrl(this, it) }
        val parsedColor = parseColor(colorHex)
        val parsedSound = resolveRawSoundUri(androidSettings?.sound)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(iconRes)
            .setContentTitle(title ?: "New Message")
            .setContentText(body ?: "You have a new notification")
            .setAutoCancel(true)
            .setPriority(resolveCompatPriority(androidSettings?.priority ?: androidSettings?.importance))

        largeIconBitmap?.let { builder.setLargeIcon(it) }
        bigPictureBitmap?.let {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(it)
                    .setSummaryText(body)
            )
        }
        parsedColor?.let { builder.color = it }
        parsedSound?.let { builder.setSound(it) }
        if (androidSettings?.vibration == true) {
            builder.setVibrate(longArrayOf(0, 250, 250, 250))
        }
        parseColor(androidSettings?.ledColor)?.let { builder.setLights(it, 800, 800) }
        pendingIntent?.let { builder.setContentIntent(it) }
        deleteIntent?.let { builder.setDeleteIntent(it) }
        actions
            ?.filter { it.id.isNotBlank() && it.displayTitle.isNotBlank() }
            ?.take(3)
            ?.forEachIndexed { index, action ->
                createActionIntent(
                    requestCode = notificationRequestCode + 10 + index,
                    metadata = metadata,
                    rawPayload = rawPayload,
                    action = action
                )?.let { actionIntent ->
                    builder.addAction(0, action.displayTitle, actionIntent)
                }
            }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = notificationRequestCode
        notificationManager.notify(notificationId, builder.build())
    }

    private fun showLiveActivity(
        liveActivity: SendrealmLiveActivity,
        payload: SendrealmPayload,
        rawPayload: String
    ) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = stableLiveActivityNotificationId(liveActivity.activityId)

        if (liveActivity.event == "end") {
            notificationManager.cancel(notificationId)
            Sendrealm.trackEvent(
                "live_activity_end",
                liveActivityEventProperties(liveActivity)
            )
            return
        }

        val channelId = "sendrealm_live_activities"
        createNotificationChannel(
            channelId,
            AndroidNotificationSettings(
                channelId = channelId,
                channelName = "Live Activities",
                channelDescription = "Ongoing updates from Sendrealm",
                importance = "low"
            )
        )

        val title = liveActivity.title ?: liveActivity.status ?: "Live Activity"
        val body = liveActivity.body ?: liveActivity.subtitle ?: liveActivity.status ?: ""
        val progressPercent = liveActivity.progress
            ?.coerceIn(0.0, 1.0)
            ?.let { (it * 100).toInt() }
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(getIconResource(null))
            .setContentTitle(title)
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        createLiveActivityContentIntent(notificationId, liveActivity, rawPayload)
            ?.let { builder.setContentIntent(it) }
        createLiveActivityDeleteIntent(notificationId, rawPayload)
            ?.let { builder.setDeleteIntent(it) }
        parseColor(liveActivity.accentColor)?.let { builder.color = it }
        progressPercent?.let { builder.setProgress(100, it, false) }

        (liveActivity.buttons ?: payload.actions)
            ?.filter { it.id.isNotBlank() && it.displayTitle.isNotBlank() }
            ?.take(3)
            ?.forEachIndexed { index, action ->
                createLiveActivityActionIntent(
                    requestCode = notificationId + 10 + index,
                    liveActivity = liveActivity,
                    rawPayload = rawPayload,
                    action = action
                )?.let { actionIntent ->
                    builder.addAction(0, action.displayTitle, actionIntent)
                }
            }

        notificationManager.notify(notificationId, builder.build())
        Sendrealm.trackEvent(
            if (liveActivity.event == "update") "live_activity_update" else "live_activity_start",
            liveActivityEventProperties(liveActivity)
        )
    }

    private fun stableLiveActivityNotificationId(activityId: String): Int {
        return activityId.hashCode() and Int.MAX_VALUE
    }

    private fun liveActivityEventProperties(
        liveActivity: SendrealmLiveActivity
    ): Map<String, Any?> {
        val properties = mutableMapOf<String, Any?>(
            "activity_id" to liveActivity.activityId
        )

        liveActivity.sendId?.takeIf { it.isNotBlank() }?.let {
            properties["send_id"] = it
        }

        liveActivity.event?.takeIf { it.isNotBlank() }?.let {
            properties["source_event"] = it
        }

        return properties
    }

    private fun createContentIntent(
        requestCode: Int,
        metadata: SendrealmMetadata?,
        rawPayload: String?
    ): PendingIntent? {
        val openIntent = Intent(this, SendrealmNotificationClickActivity::class.java).apply {
            action = Sendrealm.ACTION_NOTIFICATION_OPEN
            putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, metadata?.notificationId)
            putExtra(Sendrealm.EXTRA_LAUNCH_URL, resolveAndroidLaunchUrl(metadata))
            putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
            putExtra(Sendrealm.EXTRA_CLICK_ID, metadata?.clickId)
            putExtra(Sendrealm.EXTRA_DELIVERY_ID, metadata?.deliveryId)
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createActionIntent(
        requestCode: Int,
        metadata: SendrealmMetadata?,
        rawPayload: String?,
        action: SendrealmNotificationAction
    ): PendingIntent? {
        val actionIntent = Intent(this, SendrealmNotificationClickActivity::class.java).apply {
            this.action = Sendrealm.ACTION_NOTIFICATION_ACTION
            putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, metadata?.notificationId)
            putExtra(
                Sendrealm.EXTRA_LAUNCH_URL,
                action.launchUrl?.takeIf { it.isNotBlank() } ?: resolveAndroidLaunchUrl(metadata)
            )
            putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
            putExtra(Sendrealm.EXTRA_ACTION_ID, action.id)
            putExtra(Sendrealm.EXTRA_CLICK_ID, metadata?.clickId)
            putExtra(Sendrealm.EXTRA_DELIVERY_ID, metadata?.deliveryId)
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun resolveAndroidLaunchUrl(metadata: SendrealmMetadata?): String? {
        return metadata?.androidLaunchUrl?.takeIf { it.isNotBlank() }
            ?: metadata?.launchUrl?.takeIf { it.isNotBlank() }
    }

    private fun createLiveActivityContentIntent(
        requestCode: Int,
        liveActivity: SendrealmLiveActivity,
        rawPayload: String?
    ): PendingIntent? {
        val openIntent = Intent(this, SendrealmNotificationClickActivity::class.java).apply {
            action = Sendrealm.ACTION_NOTIFICATION_OPEN
            putExtra(Sendrealm.EXTRA_LAUNCH_URL, liveActivity.launchUrl)
            putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createLiveActivityActionIntent(
        requestCode: Int,
        liveActivity: SendrealmLiveActivity,
        rawPayload: String?,
        action: SendrealmNotificationAction
    ): PendingIntent? {
        val actionIntent = Intent(this, SendrealmNotificationClickActivity::class.java).apply {
            this.action = Sendrealm.ACTION_NOTIFICATION_ACTION
            putExtra(Sendrealm.EXTRA_LAUNCH_URL, action.launchUrl ?: liveActivity.launchUrl)
            putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
            putExtra(Sendrealm.EXTRA_ACTION_ID, action.id)
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            actionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createLiveActivityDeleteIntent(
        requestCode: Int,
        rawPayload: String?
    ): PendingIntent? {
        val dismissIntent = Intent(this, SendrealmNotificationActionReceiver::class.java).apply {
            action = Sendrealm.ACTION_NOTIFICATION_DISMISS
            putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        }

        return PendingIntent.getBroadcast(
            this,
            requestCode + 1,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createDeleteIntent(
        requestCode: Int,
        metadata: SendrealmMetadata?
    ): PendingIntent? {
        val notificationId = metadata?.notificationId ?: return null
        val dismissIntent = Intent(this, SendrealmNotificationActionReceiver::class.java).apply {
            action = Sendrealm.ACTION_NOTIFICATION_DISMISS
            putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, notificationId)
        }

        return PendingIntent.getBroadcast(
            this,
            requestCode + 1,
            dismissIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel(
        channelId: String,
        androidSettings: AndroidNotificationSettings?
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                androidSettings?.channelName?.takeIf { it.isNotBlank() } ?: getChannelName(channelId),
                resolveChannelImportance(androidSettings?.importance)
            ).apply {
                description = androidSettings?.channelDescription ?: "Notifications from Sendrealm"
                enableVibration(androidSettings?.vibration ?: true)

                resolveRawSoundUri(androidSettings?.sound)?.let { soundUri ->
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, audioAttributes)
                }

                parseColor(androidSettings?.ledColor)?.let { ledColor ->
                    enableLights(true)
                    lightColor = ledColor
                }

                lockscreenVisibility = resolveLockscreenVisibility(
                    androidSettings?.lockscreenVisibility
                )
            }

            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun resolveChannelImportance(importance: String?): Int {
        return when (importance?.lowercase()) {
            "min" -> NotificationManager.IMPORTANCE_MIN
            "low" -> NotificationManager.IMPORTANCE_LOW
            "default" -> NotificationManager.IMPORTANCE_DEFAULT
            "max", "high" -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_HIGH
        }
    }

    private fun resolveCompatPriority(priority: String?): Int {
        return when (priority?.lowercase()) {
            "min" -> NotificationCompat.PRIORITY_MIN
            "low" -> NotificationCompat.PRIORITY_LOW
            "default" -> NotificationCompat.PRIORITY_DEFAULT
            "max" -> NotificationCompat.PRIORITY_MAX
            "high" -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_HIGH
        }
    }

    private fun resolveLockscreenVisibility(visibility: String?): Int {
        return when (visibility?.lowercase()) {
            "private" -> Notification.VISIBILITY_PRIVATE
            "secret" -> Notification.VISIBILITY_SECRET
            else -> Notification.VISIBILITY_PUBLIC
        }
    }

    private fun resolveRawSoundUri(soundName: String?): Uri? {
        val normalizedSoundName = soundName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rawResourceId = resources.getIdentifier(
            normalizedSoundName.removeSuffix(".mp3").removeSuffix(".wav"),
            "raw",
            packageName
        )

        return if (rawResourceId != 0) {
            Uri.parse("android.resource://$packageName/$rawResourceId")
        } else {
            Uri.parse(normalizedSoundName)
        }
    }

    private fun getChannelName(channelId: String): String {
        return when (channelId) {
            "general" -> "General Notifications"
            "important" -> "Important Notifications"
            "updates" -> "Updates"
            else -> "Push Notifications"
        }
    }

    private fun getIconResource(iconName: String?): Int {
        if (!iconName.isNullOrBlank()) {
            val drawableId = resources.getIdentifier(iconName, "drawable", packageName)
            if (drawableId != 0) {
                return drawableId
            }
        }

        val appIcon = applicationInfo.icon
        return if (appIcon != 0) appIcon else android.R.drawable.ic_dialog_info
    }

    private fun parseColor(colorHex: String?): Int? {
        if (colorHex.isNullOrBlank()) {
            return null
        }

        return try {
            Color.parseColor(colorHex)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    internal fun testingParseSendrealmNotification(jsonData: String) {
        parseSendrealmNotification(jsonData)
    }

    internal fun testingSetBitmapLoader(loader: ((Context, String) -> Bitmap?)?) {
        testingBitmapLoader = loader
    }
}
