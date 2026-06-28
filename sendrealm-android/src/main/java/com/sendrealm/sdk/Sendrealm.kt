package com.sendrealm.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Color
import android.media.AudioAttributes
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.sendrealm.sdk.network.RemoteNotificationChannel
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.max

private const val PERMISSION_REQUEST_CODE = 1001

object Sendrealm {
    private const val TAG = "Sendrealm"
    private const val PREFS_NAME = "sendrealm_push"
    private const val KEY_APP_ID = "app_id"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_PLATFORM = "platform"
    private const val KEY_ENVIRONMENT = "environment"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_EXTERNAL_USER_ID = "external_user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_LAST_TOKEN = "last_token"
    private const val KEY_SUBSCRIBED = "subscribed"
    private const val KEY_PERMISSION_GRANTED = "permission_granted"
    private const val KEY_LAST_REGISTRATION_REFRESH_AT = "last_registration_refresh_at"
    private const val KEY_LAST_CHANNEL_SYNC_AT = "last_channel_sync_at"
    private const val KEY_SYNCED_CHANNEL_IDS = "synced_channel_ids"
    private const val KEY_PENDING_EVENTS = "pending_events"
    private const val KEY_PENDING_TAGS = "pending_tags"
    private const val KEY_PENDING_REGISTRATIONS = "pending_registrations"
    private const val KEY_PENDING_SUBSCRIPTIONS = "pending_subscriptions"
    private const val KEY_API_URL_SOURCE = "api_url_source"
    private const val KEY_BADGE_COUNT = "badge_count"
    private const val KEY_FOREGROUND_DISPLAY = "foreground_display"
    private const val KEY_LAST_INIT_RESULT = "last_init_result"
    private const val KEY_LAST_REGISTER_RESULT = "last_register_result"
    private const val KEY_LAST_SDK_ERROR = "last_sdk_error"
    private const val KEY_LAST_NOTIFICATION_PAYLOAD = "last_notification_payload"
    private const val KEY_LAST_OPEN_PAYLOAD = "last_open_payload"
    private const val KEY_RECENT_OPEN_KEYS = "recent_open_keys"
    private const val SDK_VERSION = "0.1.1"
    private const val REGISTRATION_REFRESH_INTERVAL_MS = 15 * 60 * 1000L
    private const val CHANNEL_SYNC_INTERVAL_MS = 5 * 60 * 1000L
    private const val REGISTRATION_RETRY_COUNT = 3

    private const val DEFAULT_BASE_URL = "https://sdk-api.sendrealm.com"

    const val EXTRA_NOTIFICATION_ID = "com.sendrealm.sdk.extra.NOTIFICATION_ID"
    const val EXTRA_LAUNCH_URL = "com.sendrealm.sdk.extra.LAUNCH_URL"
    const val EXTRA_PAYLOAD = "com.sendrealm.sdk.extra.PAYLOAD"
    internal const val ACTION_NOTIFICATION_OPEN = "com.sendrealm.sdk.action.NOTIFICATION_OPEN"
    internal const val ACTION_NOTIFICATION_ACTION = "com.sendrealm.sdk.action.NOTIFICATION_ACTION"
    internal const val ACTION_NOTIFICATION_DISMISS = "com.sendrealm.sdk.action.NOTIFICATION_DISMISS"
    private const val EXTRA_TRACKED = "com.sendrealm.sdk.extra.TRACKED"
    private const val EXTRA_HANDLED = "com.sendrealm.sdk.extra.HANDLED"
    const val EXTRA_ACTION_ID = "com.sendrealm.sdk.extra.ACTION_ID"
    const val EXTRA_CLICK_ID = "com.sendrealm.sdk.extra.CLICK_ID"
    const val EXTRA_DELIVERY_ID = "com.sendrealm.sdk.extra.DELIVERY_ID"

    private val gson = Gson()
    private val gsonWithNulls = GsonBuilder().serializeNulls().create()
    private val queuedEventsType = object : TypeToken<MutableList<QueuedEvent>>() {}.type
    private val queuedTagsType = object : TypeToken<MutableMap<String, Any?>>() {}.type
    private val queuedRegistrationsType = object : TypeToken<MutableList<QueuedRegistration>>() {}.type
    private val queuedSubscriptionsType = object : TypeToken<MutableList<QueuedSubscription>>() {}.type
    private val recentOpenKeysType = object : TypeToken<MutableMap<String, Long>>() {}.type
    private val syncedChannelIdsType = object : TypeToken<MutableList<String>>() {}.type
    private val operationResultType = object : TypeToken<SendrealmOperationResult>() {}.type
    private val sdkErrorType = object : TypeToken<SendrealmSdkError>() {}.type
    private val pendingQueueLock = Any()
    private val clickListeners = CopyOnWriteArraySet<SendrealmNotificationClickListener>()
    private val actionListeners = CopyOnWriteArraySet<SendrealmNotificationActionListener>()
    private val permissionObservers = CopyOnWriteArraySet<SendrealmPermissionObserver>()
    private val subscriptionObservers = CopyOnWriteArraySet<SendrealmSubscriptionObserver>()
    private val foregroundListeners = CopyOnWriteArraySet<SendrealmForegroundNotificationListener>()
    private val silentListeners = CopyOnWriteArraySet<SendrealmSilentNotificationListener>()

    private var applicationContext: Context? = null
    private var lifecycleCallbacksRegistered = false
    private var startedActivities = 0
    private var testingFcmTokenProvider: (() -> String?)? = null
    private var testingFcmDeleteResult: Boolean = true

    @Volatile
    private var isForeground = false

    var externalUserId: String? = null
        private set

    var userEmail: String? = null
        private set

    fun init(
        context: Context,
        appId: String,
        platform: String? = null,
        callback: ((String?) -> Unit)? = null
    ) {
        val config = SendrealmConfig().setPlatform(platform ?: "android")
        initialize(context, appId, config, callback)
    }

    fun initialize(
        context: Context,
        appId: String,
        config: SendrealmConfig = SendrealmConfig(),
        callback: ((String?) -> Unit)? = null
    ) {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)
        persistConfig(context, appId, config)
        notifyPermissionChangeIfNeeded(context, force = true, trackServerEvent = false)

        if (config.autoRequestPermission) {
            maybeRequestNotificationPermission(context)
        }

        thread {
            val appContext = context.applicationContext
            val apiClient = createApiClient(appContext, config.baseUrl)
            val response = apiClient.initializeBlocking(
                appId = appId,
                platform = getStoredPlatform(appContext),
                environment = getStoredEnvironment(appContext),
                deviceId = getStoredDeviceId(appContext),
                appVersion = getAppVersion(appContext),
                sdkVersion = SDK_VERSION,
                osVersion = getOsVersion(),
                deviceLocale = getDeviceLocale(),
                timezone = getTimezoneId(),
                androidPackageName = appContext.packageName,
                deviceModel = getDeviceModel(),
                apiUrlSource = getStoredApiUrlSource(appContext),
                permissionStatus = getPermissionStatus(appContext),
                subscribed = isSubscribed(appContext)
            )

            if (response == null) {
                saveOperationResult(appContext, KEY_LAST_INIT_RESULT, false, "Failed to initialize device with push API")
                saveSdkError(appContext, "E_INIT_FAILED", "Failed to initialize device with push API")
                Log.e(TAG, "Failed to initialize device with push API")
                postResult(callback, null)
                return@thread
            }

            saveOperationResult(appContext, KEY_LAST_INIT_RESULT, true)
            saveString(appContext, KEY_DEVICE_ID, response.data.deviceId)

            if (!ensureFirebaseIsReady(appContext, response.data.fcmApp)) {
                Log.e(TAG, "Failed to prepare Firebase for token retrieval")
                postResult(callback, null)
                return@thread
            }

            val token = if (config.forceRefreshRegistrationToken) {
                awaitFreshFcmToken()
            } else {
                awaitFcmToken()
            }
            if (token == null) {
                Log.e(TAG, "Failed to obtain FCM token")
                postResult(callback, null)
                return@thread
            }

            saveString(appContext, KEY_LAST_TOKEN, token)

            val registerResponse = registerCurrentTokenWithRetryBlocking(appContext, token)
            if (registerResponse == null) {
                enqueueRegistration(appContext, token, "init_register_failed")
                Log.e(TAG, "Failed to register device with push API")
                postResult(callback, null)
                return@thread
            }

            Log.d(TAG, "Device registered successfully with deviceId=${registerResponse.data.deviceId}")
            saveLong(appContext, KEY_LAST_REGISTRATION_REFRESH_AT, System.currentTimeMillis())
            syncNotificationChannelsBlocking(appContext, force = true)
            flushPendingWorkBlocking(appContext)
            postResult(callback, token)
        }
    }

    fun addNotificationClickListener(listener: SendrealmNotificationClickListener) {
        clickListeners.add(listener)
    }

    fun removeNotificationClickListener(listener: SendrealmNotificationClickListener) {
        clickListeners.remove(listener)
    }

    fun addNotificationActionListener(listener: SendrealmNotificationActionListener) {
        actionListeners.add(listener)
    }

    fun removeNotificationActionListener(listener: SendrealmNotificationActionListener) {
        actionListeners.remove(listener)
    }

    fun addForegroundNotificationListener(listener: SendrealmForegroundNotificationListener) {
        foregroundListeners.add(listener)
    }

    fun removeForegroundNotificationListener(listener: SendrealmForegroundNotificationListener) {
        foregroundListeners.remove(listener)
    }

    fun addSilentNotificationListener(listener: SendrealmSilentNotificationListener) {
        silentListeners.add(listener)
    }

    fun removeSilentNotificationListener(listener: SendrealmSilentNotificationListener) {
        silentListeners.remove(listener)
    }

    fun addPermissionObserver(observer: SendrealmPermissionObserver) {
        permissionObservers.add(observer)
        applicationContext?.let { observer.onPermissionChanged(hasNotificationPermission(it)) }
    }

    fun removePermissionObserver(observer: SendrealmPermissionObserver) {
        permissionObservers.remove(observer)
    }

    fun addSubscriptionObserver(observer: SendrealmSubscriptionObserver) {
        subscriptionObservers.add(observer)
        applicationContext?.let { observer.onSubscriptionChanged(isSubscribed(it)) }
    }

    fun removeSubscriptionObserver(observer: SendrealmSubscriptionObserver) {
        subscriptionObservers.remove(observer)
    }

    fun login(userId: String, email: String? = null) {
        externalUserId = normalize(userId)
        userEmail = normalize(email)?.lowercase(Locale.US)

        val appContext = applicationContext
        if (appContext == null) {
            Log.w(TAG, "login() called before init(); identity will not be synced yet.")
            return
        }

        val prefs = getPrefs(appContext)
        prefs.edit {
            putString(KEY_EXTERNAL_USER_ID, externalUserId)
            putString(KEY_USER_EMAIL, userEmail)
        }

        refreshRegistration()
        Log.d(TAG, "User logged in: $userId")
    }

    fun logout() {
        externalUserId = null
        userEmail = null

        val appContext = applicationContext
        if (appContext == null) {
            Log.w(TAG, "logout() called before init(); local identity cleared only.")
            return
        }

        val prefs = getPrefs(appContext)
        prefs.edit {
            remove(KEY_EXTERNAL_USER_ID)
            remove(KEY_USER_EMAIL)
        }

        refreshRegistration()
        Log.d(TAG, "User logged out")
    }

    fun requestPermission(activity: Activity) {
        maybeRequestNotificationPermission(activity)
    }

    fun refreshPermissionState(context: Context, trackServerEvent: Boolean = true) {
        notifyPermissionChangeIfNeeded(
            context.applicationContext,
            force = true,
            trackServerEvent = trackServerEvent
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getPermissionStatus(context: Context): String {
        if (hasNotificationPermission(context)) {
            return "authorized"
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return "denied"
        }

        return if (getStoredPermissionGranted(context.applicationContext) == null) {
            "not_determined"
        } else {
            "denied"
        }
    }

    fun openNotificationSettings(context: Context): Boolean {
        val appContext = context.applicationContext
        val intent = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${appContext.packageName}")
            }
        }).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            appContext.startActivity(intent)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open notification settings", error)
            false
        }
    }

    fun setBadgeCount(context: Context, count: Int): Boolean {
        val appContext = context.applicationContext
        val normalizedCount = count.coerceAtLeast(0)
        saveInt(appContext, KEY_BADGE_COUNT, normalizedCount)

        val launchIntent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val launcherClassName = launchIntent?.component?.className
        val badgeIntent = Intent("android.intent.action.BADGE_COUNT_UPDATE").apply {
            putExtra("badge_count", normalizedCount)
            putExtra("badge_count_package_name", appContext.packageName)
            putExtra("badge_count_class_name", launcherClassName)
        }

        return try {
            appContext.sendBroadcast(badgeIntent)
            true
        } catch (error: Exception) {
            Log.w(TAG, "Launcher badge count broadcast failed", error)
            false
        }
    }

    fun clearBadge(context: Context): Boolean {
        return setBadgeCount(context, 0)
    }

    fun setForegroundPresentation(
        context: Context,
        options: SendrealmForegroundPresentationOptions
    ): Boolean {
        saveBoolean(context.applicationContext, KEY_FOREGROUND_DISPLAY, options.display)
        return true
    }

    fun shouldDisplayForegroundNotification(context: Context): Boolean {
        val prefs = getPrefs(context.applicationContext)
        return if (prefs.contains(KEY_FOREGROUND_DISPLAY)) {
            prefs.getBoolean(KEY_FOREGROUND_DISPLAY, true)
        } else {
            true
        }
    }

    fun getDeviceId(): String? {
        val appContext = applicationContext ?: return null
        return getStoredDeviceId(appContext)
    }

    fun isSubscribed(): Boolean {
        val appContext = applicationContext ?: return false
        return isSubscribed(appContext)
    }

    fun getState(): SendrealmState {
        val appContext = applicationContext
            ?: return SendrealmState(
                initialized = false,
                registered = false,
                permissionGranted = false,
                subscribed = false,
                deviceId = null,
                registrationToken = null,
                tokenStatus = "missing",
                externalUserId = externalUserId,
                userEmail = userEmail,
                platform = "android",
                environment = "production",
                sdkVersion = SDK_VERSION
            )

        return getState(appContext)
    }

    fun getState(context: Context): SendrealmState {
        val appContext = context.applicationContext
        val deviceId = getStoredDeviceId(appContext)
        val token = getStoredRegistrationToken(appContext)

        return SendrealmState(
            initialized = !getStoredAppId(appContext).isNullOrBlank(),
            registered = !deviceId.isNullOrBlank() && !token.isNullOrBlank(),
            permissionGranted = hasNotificationPermission(appContext),
            subscribed = isSubscribed(appContext),
            deviceId = deviceId,
            registrationToken = token,
            tokenStatus = when {
                token.isNullOrBlank() -> "missing"
                isSubscribed(appContext) -> "registered"
                else -> "unsubscribed"
            },
            externalUserId = getStoredExternalUserId(appContext),
            userEmail = getStoredUserEmail(appContext),
            platform = getStoredPlatform(appContext),
            environment = getStoredEnvironment(appContext),
            sdkVersion = SDK_VERSION
        )
    }

    fun getDiagnostics(context: Context): SendrealmDiagnostics {
        val appContext = context.applicationContext
        val token = getStoredRegistrationToken(appContext)
        return SendrealmDiagnostics(
            appId = getStoredAppId(appContext),
            apiUrl = resolveBaseUrl(appContext),
            apiUrlSource = getStoredApiUrlSource(appContext),
            sdkVersion = SDK_VERSION,
            platform = getStoredPlatform(appContext),
            environment = getStoredEnvironment(appContext),
            deviceId = getStoredDeviceId(appContext),
            registrationTokenPresent = !token.isNullOrBlank(),
            permissionStatus = getPermissionStatus(appContext),
            subscribed = isSubscribed(appContext),
            appVersion = getAppVersion(appContext),
            deviceModel = getDeviceModel(),
            osVersion = getOsVersion(),
            locale = getDeviceLocale(),
            timezone = getTimezoneId(),
            lastInitResult = getStoredOperationResult(appContext, KEY_LAST_INIT_RESULT),
            lastRegisterResult = getStoredOperationResult(appContext, KEY_LAST_REGISTER_RESULT),
            lastSdkError = getStoredSdkError(appContext),
            queueCounts = getQueueCounts(appContext),
            lastNotificationPayload = getPrefs(appContext).getString(KEY_LAST_NOTIFICATION_PAYLOAD, null),
            lastOpenPayload = getPrefs(appContext).getString(KEY_LAST_OPEN_PAYLOAD, null)
        )
    }

    fun createNotificationChannel(
        context: Context,
        channel: SendrealmNotificationChannelConfig
    ): Boolean {
        if (channel.id.isBlank() || channel.name.isBlank()) {
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }

        return try {
            val notificationChannel = NotificationChannel(
                channel.id,
                channel.name,
                channel.importance
            ).apply {
                description = channel.description
                enableVibration(channel.enableVibration)
                vibrationPattern = channel.vibrationPattern
                enableLights(channel.enableLights)
                channel.lightColor?.let { lightColor = it }
                lockscreenVisibility = channel.lockscreenVisibility
                setShowBadge(channel.showBadge)

                resolveRawSoundUri(context, channel.soundName)?.let { soundUri ->
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, audioAttributes)
                }
            }

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(notificationChannel)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to create notification channel ${channel.id}", error)
            false
        }
    }

    fun deleteNotificationChannel(context: Context, channelId: String): Boolean {
        if (channelId.isBlank()) {
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true
        }

        return try {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.deleteNotificationChannel(channelId)
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to delete notification channel $channelId", error)
            false
        }
    }

    fun getNotificationChannels(context: Context): List<SendrealmNotificationChannelState> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return emptyList()
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return notificationManager.notificationChannels.map { channel ->
            SendrealmNotificationChannelState(
                id = channel.id,
                name = channel.name?.toString().orEmpty(),
                description = channel.description,
                importance = channel.importance,
                soundUri = channel.sound?.toString(),
                enableVibration = channel.shouldVibrate(),
                enableLights = channel.shouldShowLights(),
                lightColor = channel.lightColor,
                lockscreenVisibility = channel.lockscreenVisibility,
                showBadge = channel.canShowBadge()
            )
        }
    }

    fun syncNotificationChannels(callback: ((Boolean) -> Unit)? = null) {
        val appContext = applicationContext
        if (appContext == null) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val success = syncNotificationChannelsBlocking(appContext, force = true)
            postBooleanResult(callback, success)
        }
    }

    fun registerLiveActivityToken(
        token: String,
        activityId: String? = null,
        tokenType: String = "android_registration",
        activityType: String? = null,
        attributesType: String? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val appContext = applicationContext
        if (appContext == null || token.isBlank()) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val appId = getStoredAppId(appContext)
            val deviceId = getStoredDeviceId(appContext)

            if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
                postBooleanResult(callback, false)
                return@thread
            }

            val success = createApiClient(appContext).registerLiveActivityTokenBlocking(
                appId = appId,
                deviceId = deviceId,
                platform = getStoredPlatform(appContext),
                tokenType = tokenType,
                token = token,
                activityId = activityId,
                activityType = activityType,
                attributesType = attributesType
            )

            postBooleanResult(callback, success)
        }
    }

    fun deleteLiveActivityToken(
        token: String,
        activityId: String? = null,
        tokenType: String = "android_registration",
        activityType: String? = null,
        attributesType: String? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val appContext = applicationContext
        if (appContext == null || token.isBlank()) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val appId = getStoredAppId(appContext)
            val deviceId = getStoredDeviceId(appContext)

            if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
                postBooleanResult(callback, false)
                return@thread
            }

            val success = createApiClient(appContext).deleteLiveActivityTokenBlocking(
                appId = appId,
                deviceId = deviceId,
                platform = getStoredPlatform(appContext),
                tokenType = tokenType,
                token = token,
                activityId = activityId,
                activityType = activityType,
                attributesType = attributesType
            )

            postBooleanResult(callback, success)
        }
    }

    fun optIn(callback: ((Boolean) -> Unit)? = null) {
        val appContext = applicationContext
        if (appContext == null) {
            postBooleanResult(callback, false)
            return
        }

        val handleToken: (String?) -> Unit = { token ->
            if (token.isNullOrBlank()) {
                Log.e(TAG, "optIn() failed because token is empty")
                postBooleanResult(callback, false)
            } else {
                saveString(appContext, KEY_LAST_TOKEN, token)
                thread {
                    val success = updateSubscriptionStateBlocking(
                        context = appContext,
                        subscribed = true,
                        registrationId = token
                    )
                    if (!success) {
                        enqueueSubscription(appContext, true, token, "opt_in_failed")
                    }
                    postBooleanResult(callback, success)
                }
            }
        }

        testingFcmTokenProvider?.let { provider ->
            handleToken(provider())
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "optIn() failed to get FCM token", task.exception)
                    postBooleanResult(callback, false)
                    return@addOnCompleteListener
                }

                handleToken(task.result)
            }
        } catch (error: Exception) {
            Log.e(TAG, "optIn() failed to request FCM token", error)
            postBooleanResult(callback, false)
        }
    }

    fun refreshRegistrationToken(
        forceRefresh: Boolean = true,
        callback: ((String?) -> Unit)? = null
    ) {
        val appContext = applicationContext
        if (appContext == null) {
            Log.w(TAG, "refreshRegistrationToken() called before init()")
            postResult(callback, null)
            return
        }

        val fetchAndRegisterToken = {
            thread {
                val token = awaitFcmToken()
                if (token.isNullOrBlank()) {
                    Log.e(TAG, "Failed to get refreshed FCM token")
                    postResult(callback, null)
                    return@thread
                }

                saveString(appContext, KEY_LAST_TOKEN, token)
                val registerResponse = registerCurrentTokenWithRetryBlocking(appContext, token)
                if (registerResponse == null) {
                    enqueueRegistration(appContext, token, "manual_refresh_failed")
                    Log.e(TAG, "Refreshed FCM token could not be synced to push API")
                    postResult(callback, null)
                } else {
                    Log.d(TAG, "Refreshed FCM token synced successfully")
                    saveLong(appContext, KEY_LAST_REGISTRATION_REFRESH_AT, System.currentTimeMillis())
                    syncNotificationChannelsBlocking(appContext, force = true)
                    flushPendingWorkBlocking(appContext)
                    postResult(callback, token)
                }
            }
        }

        if (!forceRefresh) {
            fetchAndRegisterToken()
            return
        }

        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { deleteTask ->
                if (!deleteTask.isSuccessful) {
                    Log.w(
                        TAG,
                        "Failed to delete stale FCM token before refresh; trying current token",
                        deleteTask.exception
                    )
                }

                fetchAndRegisterToken()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to request stale FCM token deletion before refresh; trying current token", error)
            fetchAndRegisterToken()
        }
    }

    fun optOut(callback: ((Boolean) -> Unit)? = null) {
        val appContext = applicationContext
        if (appContext == null) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val success = updateSubscriptionStateBlocking(
                context = appContext,
                subscribed = false,
                registrationId = null
            )
            if (!success) {
                enqueueSubscription(appContext, false, null, "opt_out_failed")
            }
            postBooleanResult(callback, success)
        }
    }

    fun addTag(key: String, value: Any?, callback: ((Boolean) -> Unit)? = null) {
        addTags(mapOf(key to value), callback)
    }

    fun addTags(tags: Map<String, Any?>, callback: ((Boolean) -> Unit)? = null) {
        val appContext = applicationContext
        if (appContext == null || tags.isEmpty()) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val appId = getStoredAppId(appContext)
            val deviceId = getStoredDeviceId(appContext)

            if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
                Log.w(TAG, "addTags() skipped because appId or deviceId is missing")
                postBooleanResult(callback, false)
                return@thread
            }

            val normalizedTags = tags.mapValues { (_, value) -> normalizeTagValue(value) }
            val success = createApiClient(appContext).updateTagsBlocking(
                appId = appId,
                deviceId = deviceId,
                platform = getStoredPlatform(appContext),
                tags = normalizedTags
            )

            if (!success) {
                enqueueTags(appContext, normalizedTags)
            }

            postBooleanResult(callback, success || normalizedTags.isNotEmpty())
        }
    }

    fun removeTag(key: String, callback: ((Boolean) -> Unit)? = null) {
        addTags(mapOf(key to null), callback)
    }

    fun trackEvent(
        eventType: String,
        properties: Map<String, Any?>? = null,
        callback: ((Boolean) -> Unit)? = null
    ) {
        val appContext = applicationContext
        if (appContext == null) {
            postBooleanResult(callback, false)
            return
        }

        thread {
            val success = trackDeviceEventBlocking(appContext, eventType, properties = properties)
            if (!success) {
                enqueueEvent(appContext, eventType, properties = properties)
            }

            postBooleanResult(callback, success || eventType.isNotBlank())
        }
    }

    fun handleNotificationOpen(intent: Intent?): SendrealmNotificationOpenResult? {
        if (intent == null) {
            return null
        }

        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        val deliveryId = intent.getStringExtra(EXTRA_DELIVERY_ID)
        val clickId = intent.getStringExtra(EXTRA_CLICK_ID)
        val actionIdentifier = intent.getStringExtra(EXTRA_ACTION_ID)
        val launchUrl = intent.getStringExtra(EXTRA_LAUNCH_URL)
        val rawPayload = intent.getStringExtra(EXTRA_PAYLOAD)
        val payload = parsePayload(rawPayload)

        if (notificationId == null && launchUrl == null && rawPayload == null) {
            return null
        }

        val result = SendrealmNotificationOpenResult(
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            payload = payload
        )

        applicationContext?.let { appContext ->
            rawPayload?.let { saveString(appContext, KEY_LAST_OPEN_PAYLOAD, it) }
        }
        val alreadyHandled = intent.getBooleanExtra(EXTRA_HANDLED, false)
        val alreadyTracked = intent.getBooleanExtra(EXTRA_TRACKED, false)
        if (!alreadyHandled) {
            clickListeners.forEach { it.onClick(result) }
            if (!actionIdentifier.isNullOrBlank()) {
                actionListeners.forEach {
                    it.onAction(
                        SendrealmNotificationActionEvent(
                            notificationId = notificationId,
                            actionIdentifier = actionIdentifier,
                            launchUrl = launchUrl,
                            rawPayload = rawPayload,
                            payload = payload
                        )
                    )
                }
            }

            val duplicateOpen = applicationContext?.let { isDuplicateOpen(it, result) } ?: false
            if (!alreadyTracked && !duplicateOpen) {
                notificationId?.let {
                    trackNotificationEventAsync("open", it)
                    if (actionIdentifier.isNullOrBlank()) {
                        trackNotificationEventAsync("click", it)
                    } else {
                        trackNotificationEventAsync("click", it, mapOf("action_id" to actionIdentifier))
                    }
                    if (!actionIdentifier.isNullOrBlank()) {
                        trackDeviceEventAsync(
                            "notification_action",
                            mapOf("notification_id" to it, "action_id" to actionIdentifier)
                        )
                    }
                }
                payload?.liveActivity?.takeIf { it.activityId.isNotBlank() }?.let { liveActivity ->
                    val properties = liveActivityEventProperties(
                        liveActivity,
                        actionIdentifier
                    )

                    trackDeviceEventAsync(
                        if (actionIdentifier.isNullOrBlank()) "live_activity_open" else "live_activity_action",
                        properties
                    )
                }
            }

            intent.putExtra(EXTRA_HANDLED, true)
        }

        return result
    }

    internal fun handleNotificationAction(context: Context, intent: Intent?) {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)

        val notificationId = intent?.getStringExtra(EXTRA_NOTIFICATION_ID)
        val deliveryId = intent?.getStringExtra(EXTRA_DELIVERY_ID)
        val clickId = intent?.getStringExtra(EXTRA_CLICK_ID)
        val actionIdentifier = intent?.getStringExtra(EXTRA_ACTION_ID)
        val rawPayload = intent?.getStringExtra(EXTRA_PAYLOAD)
        val payload = parsePayload(rawPayload)
        val launchUrl = resolvePayloadLaunchUrl(
            intent?.getStringExtra(EXTRA_LAUNCH_URL),
            payload
        )
        val result = SendrealmNotificationOpenResult(
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            payload = payload
        )
        val duplicateOpen = isDuplicateOpen(context.applicationContext, result)

        rawPayload?.let { saveString(context.applicationContext, KEY_LAST_OPEN_PAYLOAD, it) }

        if (!actionIdentifier.isNullOrBlank()) {
            actionListeners.forEach {
                it.onAction(
                    SendrealmNotificationActionEvent(
                        notificationId = notificationId,
                        actionIdentifier = actionIdentifier,
                        launchUrl = launchUrl,
                        rawPayload = rawPayload,
                        payload = payload
                    )
                )
            }
        }

        notificationId?.takeIf { !duplicateOpen }?.let {
            trackNotificationEventAsync("open", it)
            if (actionIdentifier.isNullOrBlank()) {
                trackNotificationEventAsync("click", it)
            } else {
                trackNotificationEventAsync("click", it, mapOf("action_id" to actionIdentifier))
            }
            if (!actionIdentifier.isNullOrBlank()) {
                trackDeviceEventAsync(
                    "notification_action",
                    mapOf("notification_id" to it, "action_id" to actionIdentifier)
                )
            }
        }
        payload?.liveActivity
            ?.takeIf { it.activityId.isNotBlank() && !duplicateOpen }
            ?.let { liveActivity ->
                val properties = liveActivityEventProperties(
                    liveActivity,
                    actionIdentifier
                )

                trackDeviceEventAsync(
                    if (actionIdentifier.isNullOrBlank()) "live_activity_open" else "live_activity_action",
                    properties
                )
            }

        val externalLaunchIntent = buildExternalLaunchIntent(
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = true
        )

        if (externalLaunchIntent != null) {
            try {
                Log.d(TAG, "Opening notification launchUrl=$launchUrl")
                context.startActivity(externalLaunchIntent)
                return
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "No activity found for launchUrl=$launchUrl, falling back to app launch")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to open launchUrl=$launchUrl, falling back to app launch", error)
            }
        }

        val appLaunchIntent = buildAppLaunchIntent(
            context = context,
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = true
        )

        if (appLaunchIntent == null) {
            Log.w(TAG, "No app launch intent available for notification action")
            return
        }

        context.startActivity(appLaunchIntent)
    }

    internal fun openNotificationDestination(context: Context, intent: Intent?) {
        if (intent == null) {
            return
        }

        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        val deliveryId = intent.getStringExtra(EXTRA_DELIVERY_ID)
        val clickId = intent.getStringExtra(EXTRA_CLICK_ID)
        val actionIdentifier = intent.getStringExtra(EXTRA_ACTION_ID)
        val rawPayload = intent.getStringExtra(EXTRA_PAYLOAD)
        val launchUrl = resolvePayloadLaunchUrl(
            intent.getStringExtra(EXTRA_LAUNCH_URL),
            parsePayload(rawPayload)
        )

        val externalLaunchIntent = buildExternalLaunchIntent(
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = true
        )?.apply {
            putExtra(EXTRA_HANDLED, true)
        }

        if (externalLaunchIntent != null) {
            try {
                Log.d(TAG, "Opening notification destination launchUrl=$launchUrl")
                context.startActivity(externalLaunchIntent)
                return
            } catch (_: ActivityNotFoundException) {
                Log.w(TAG, "No activity found for action launchUrl=$launchUrl, falling back to app launch")
            } catch (error: Exception) {
                Log.e(TAG, "Failed to open action launchUrl=$launchUrl, falling back to app launch", error)
            }
        }

        val destinationIntent = buildAppLaunchIntent(
            context = context,
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = true
        )?.apply {
            putExtra(EXTRA_HANDLED, true)
        }

        if (destinationIntent == null) {
            Log.w(TAG, "No destination available for notification action")
            return
        }

        try {
            context.startActivity(destinationIntent)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open notification destination", error)
        }
    }

    private fun resolvePayloadLaunchUrl(
        launchUrl: String?,
        payload: SendrealmPayload?
    ): String? {
        return launchUrl?.takeIf { it.isNotBlank() }
            ?: payload?.metadata?.androidLaunchUrl?.takeIf { it.isNotBlank() }
            ?: payload?.metadata?.launchUrl?.takeIf { it.isNotBlank() }
            ?: payload?.liveActivity?.launchUrl?.takeIf { it.isNotBlank() }
    }

    private fun liveActivityEventProperties(
        liveActivity: SendrealmLiveActivity,
        actionIdentifier: String? = null
    ): MutableMap<String, Any?> {
        val properties = mutableMapOf<String, Any?>(
            "activity_id" to liveActivity.activityId
        )

        liveActivity.sendId?.takeIf { it.isNotBlank() }?.let {
            properties["send_id"] = it
        }

        if (!actionIdentifier.isNullOrBlank()) {
            properties["action_id"] = actionIdentifier
        }

        return properties
    }

    internal fun handleNotificationDismissed(context: Context, intent: Intent?) {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)

        val notificationId = intent?.getStringExtra(EXTRA_NOTIFICATION_ID)
        val rawPayload = intent?.getStringExtra(EXTRA_PAYLOAD)
        val payload = parsePayload(rawPayload)

        notificationId?.let { trackNotificationEventAsync("dismiss", it) }
        payload?.liveActivity?.takeIf { it.activityId.isNotBlank() }?.let { liveActivity ->
            trackDeviceEventAsync(
                "live_activity_dismiss",
                liveActivityEventProperties(liveActivity)
            )
        }
    }

    internal fun onNewToken(context: Context, token: String) {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)
        saveString(context, KEY_LAST_TOKEN, token)

        thread {
            val registerResponse = registerCurrentTokenWithRetryBlocking(context.applicationContext, token)
            if (registerResponse == null) {
                enqueueRegistration(context.applicationContext, token, "fcm_refresh_failed")
                Log.e(TAG, "Token refresh could not be synced to push API")
            } else {
                saveLong(context.applicationContext, KEY_LAST_REGISTRATION_REFRESH_AT, System.currentTimeMillis())
                syncNotificationChannelsBlocking(context.applicationContext, force = true)
                flushPendingWorkBlocking(context.applicationContext)
                Log.d(TAG, "Token refresh synced successfully")
            }
        }
    }

    internal fun onNotificationReceived(
        context: Context,
        payload: SendrealmPayload,
        rawPayload: String
    ): SendrealmForegroundNotificationEvent {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)

        val event = SendrealmForegroundNotificationEvent(
            payload = payload,
            rawPayload = rawPayload,
            isForeground = isForeground
        )

        saveString(context.applicationContext, KEY_LAST_NOTIFICATION_PAYLOAD, rawPayload)

        payload.metadata?.notificationId?.let {
            trackNotificationEventAsync("delivery", it)

            if (event.isForeground) {
                trackNotificationEventAsync("foreground_display", it)
            }
        }

        if (event.isForeground) {
            foregroundListeners.forEach { it.onWillDisplay(event) }
        } else {
            trackDeviceEventAsync("background_notification_received")
        }

        return event
    }

    internal fun onSilentNotificationReceived(
        context: Context,
        payload: SendrealmPayload,
        rawPayload: String
    ): SendrealmSilentNotificationEvent {
        applicationContext = context.applicationContext
        registerLifecycleCallbacks(context)
        saveString(context.applicationContext, KEY_LAST_NOTIFICATION_PAYLOAD, rawPayload)

        val event = SendrealmSilentNotificationEvent(
            payload = payload,
            rawPayload = rawPayload,
            isForeground = isForeground
        )

        payload.metadata?.notificationId?.let {
            trackNotificationEventAsync("delivery", it)
        }
        trackDeviceEventAsync("background_notification_received")
        silentListeners.forEach { it.onSilentNotification(event) }
        return event
    }

    internal fun testingResetState(context: Context) {
        val appContext = context.applicationContext
        getPrefs(appContext).edit(commit = true) {
            clear()
        }
        applicationContext = null
        lifecycleCallbacksRegistered = false
        startedActivities = 0
        isForeground = false
        externalUserId = null
        userEmail = null
        clickListeners.clear()
        actionListeners.clear()
        permissionObservers.clear()
        subscriptionObservers.clear()
        foregroundListeners.clear()
        silentListeners.clear()
        testingFcmTokenProvider = null
        testingFcmDeleteResult = true
    }

    internal fun testingSetState(
        context: Context,
        appId: String? = "app_123",
        baseUrl: String? = null,
        platform: String = "android",
        environment: String = "production",
        deviceId: String? = "device_123",
        registrationToken: String? = null,
        externalUserId: String? = null,
        userEmail: String? = null,
        subscribed: Boolean? = null,
        permissionGranted: Boolean? = null
    ) {
        val appContext = context.applicationContext
        applicationContext = appContext
        this.externalUserId = normalize(externalUserId)
        this.userEmail = normalize(userEmail)?.lowercase(Locale.US)

        getPrefs(appContext).edit(commit = true) {
            putString(KEY_APP_ID, appId)
            putString(KEY_BASE_URL, normalize(baseUrl)?.trimEnd('/'))
            putString(KEY_PLATFORM, normalize(platform) ?: "android")
            putString(KEY_ENVIRONMENT, normalizeEnvironment(environment))
            putString(KEY_DEVICE_ID, deviceId)
            putString(KEY_LAST_TOKEN, registrationToken)
            putString(KEY_EXTERNAL_USER_ID, this@Sendrealm.externalUserId)
            putString(KEY_USER_EMAIL, this@Sendrealm.userEmail)
            if (subscribed == null) {
                remove(KEY_SUBSCRIBED)
            } else {
                putBoolean(KEY_SUBSCRIBED, subscribed)
            }
            if (permissionGranted == null) {
                remove(KEY_PERMISSION_GRANTED)
            } else {
                putBoolean(KEY_PERMISSION_GRANTED, permissionGranted)
            }
        }
    }

    internal fun testingQueueCounts(context: Context): SendrealmQueueCounts =
        getQueueCounts(context.applicationContext)

    internal fun testingQueuedEvents(context: Context): List<Map<String, Any?>> =
        getPendingEvents(context.applicationContext).map { event ->
            mapOf(
                "eventType" to event.eventType,
                "notificationId" to event.notificationId,
                "properties" to event.properties,
                "idempotencyKey" to event.idempotencyKey,
                "queuedAt" to event.queuedAt
            )
        }

    internal fun testingQueuedTags(context: Context): Map<String, Any?> =
        getPendingTags(context.applicationContext).toMap()

    internal fun testingQueuedRegistrations(context: Context): List<Map<String, Any?>> =
        getPendingRegistrations(context.applicationContext).map { registration ->
            mapOf(
                "registrationId" to registration.registrationId,
                "reason" to registration.reason,
                "idempotencyKey" to registration.idempotencyKey,
                "retryCount" to registration.retryCount,
                "queuedAt" to registration.queuedAt,
                "nextAttemptAt" to registration.nextAttemptAt
            )
        }

    internal fun testingQueuedSubscriptions(context: Context): List<Map<String, Any?>> =
        getPendingSubscriptions(context.applicationContext).map { subscription ->
            mapOf(
                "subscribed" to subscription.subscribed,
                "registrationId" to subscription.registrationId,
                "reason" to subscription.reason,
                "idempotencyKey" to subscription.idempotencyKey,
                "retryCount" to subscription.retryCount,
                "queuedAt" to subscription.queuedAt,
                "nextAttemptAt" to subscription.nextAttemptAt
            )
        }

    internal fun testingEnqueueRegistration(
        context: Context,
        registrationId: String,
        reason: String = "test"
    ) {
        enqueueRegistration(context.applicationContext, registrationId, reason)
    }

    internal fun testingEnqueueSubscription(
        context: Context,
        subscribed: Boolean,
        registrationId: String? = null,
        reason: String = "test"
    ) {
        enqueueSubscription(context.applicationContext, subscribed, registrationId, reason)
    }

    internal fun testingFlushPendingWorkBlocking(context: Context) {
        flushPendingWorkBlocking(context.applicationContext)
    }

    internal fun testingResolveLaunchUrl(explicitLaunchUrl: String?, rawPayload: String?): String? =
        resolvePayloadLaunchUrl(explicitLaunchUrl, parsePayload(rawPayload))

    internal fun testingRecentOpenKeys(context: Context): Map<String, Long> =
        getRecentOpenKeys(context.applicationContext).toMap()

    internal fun testingRetryDelayMs(retryCount: Int): Long = retryDelayMs(retryCount)

    internal fun testingSetFcmTokenProvider(
        provider: (() -> String?)?,
        deleteResult: Boolean = true
    ) {
        testingFcmTokenProvider = provider
        testingFcmDeleteResult = deleteResult
    }

    internal fun testingAwaitFcmToken(): String? = awaitFcmToken()

    internal fun testingAwaitFreshFcmToken(): String? = awaitFreshFcmToken()

    internal fun testingMaybeRequestNotificationPermission(context: Context) {
        maybeRequestNotificationPermission(context)
    }

    internal fun testingRefreshRegistrationIfNeeded(context: Context, force: Boolean = false) {
        refreshRegistrationIfNeeded(context.applicationContext, force)
    }

    internal fun testingBuildExternalLaunchIntent(
        notificationId: String?,
        deliveryId: String?,
        clickId: String?,
        actionIdentifier: String?,
        launchUrl: String?,
        rawPayload: String?,
        tracked: Boolean
    ): Intent? =
        buildExternalLaunchIntent(
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = tracked
        )

    internal fun testingBuildAppLaunchIntent(
        context: Context,
        notificationId: String?,
        deliveryId: String?,
        clickId: String?,
        actionIdentifier: String?,
        launchUrl: String?,
        rawPayload: String?,
        tracked: Boolean
    ): Intent? =
        buildAppLaunchIntent(
            context = context,
            notificationId = notificationId,
            deliveryId = deliveryId,
            clickId = clickId,
            actionIdentifier = actionIdentifier,
            launchUrl = launchUrl,
            rawPayload = rawPayload,
            tracked = tracked
        )

    internal fun testingCreateLifecycleCallbacks(): Application.ActivityLifecycleCallbacks =
        createLifecycleCallbacks()

    internal fun testingUtilityDiagnostics(context: Context): Map<String, Any?> {
        val appContext = context.applicationContext
        saveInt(appContext, KEY_BADGE_COUNT, 7)
        saveLong(appContext, KEY_LAST_CHANNEL_SYNC_AT, 123L)

        return mapOf(
            "overrideBaseUrl" to resolveBaseUrl(appContext, " https://push.example.com/// "),
            "storedBaseUrl" to resolveBaseUrl(appContext),
            "defaultSound" to resolveRawSoundUri(appContext, "default")?.toString(),
            "customSound" to resolveRawSoundUri(appContext, "alert.wav")?.toString(),
            "importanceNone" to resolveChannelImportance("none"),
            "visibilitySecret" to resolveLockscreenVisibility("secret"),
            "invalidColor" to parseColor("not-a-color"),
            "webLaunchUrl" to normalizeLaunchUrl("example.com/path"),
            "plainLaunchUrl" to normalizeLaunchUrl("open app"),
            "blankPayload" to parsePayload(" "),
            "badPayload" to parsePayload("{bad"),
            "normalizedNumber" to normalizeTagValue(42),
            "normalizedBoolean" to normalizeTagValue(true),
            "normalizedObject" to normalizeTagValue(StringBuilder(" object-value "))
        )
    }

    private fun refreshRegistration() {
        val appContext = applicationContext ?: return
        val handleToken: (String?) -> Unit = { token ->
            if (token.isNullOrBlank()) {
                Log.e(TAG, "Identity change refresh skipped because token is empty")
            } else {
                saveString(appContext, KEY_LAST_TOKEN, token)
                thread {
                    val registerResponse = registerCurrentTokenWithRetryBlocking(appContext, token)
                    if (registerResponse == null) {
                        enqueueRegistration(appContext, token, "identity_change_failed")
                        Log.e(TAG, "Failed to sync identity change with push API")
                    } else {
                        Log.d(TAG, "Registration synced after identity change")
                        saveLong(appContext, KEY_LAST_REGISTRATION_REFRESH_AT, System.currentTimeMillis())
                        syncNotificationChannelsBlocking(appContext, force = true)
                        flushPendingWorkBlocking(appContext)
                    }
                }
            }
        }

        testingFcmTokenProvider?.let { provider ->
            handleToken(provider())
            return
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.e(TAG, "Failed to refresh registration after identity change", task.exception)
                    return@addOnCompleteListener
                }

                handleToken(task.result)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to request FCM token after identity change", error)
        }
    }

    private fun refreshRegistrationIfNeeded(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val lastRefreshAt = getPrefs(appContext).getLong(KEY_LAST_REGISTRATION_REFRESH_AT, 0L)
        val now = System.currentTimeMillis()

        if (!force && now - lastRefreshAt < REGISTRATION_REFRESH_INTERVAL_MS) {
            return
        }

        thread {
            val token = awaitFcmToken()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "Resume token refresh skipped because FCM token is unavailable")
                return@thread
            }

            val previousToken = getStoredRegistrationToken(appContext)
            if (!force && previousToken == token && getStoredSubscribed(appContext) == true) {
                saveLong(appContext, KEY_LAST_REGISTRATION_REFRESH_AT, now)
                syncNotificationChannelsIfNeeded(appContext)
                flushPendingWorkBlocking(appContext)
                return@thread
            }

            saveString(appContext, KEY_LAST_TOKEN, token)
            val registerResponse = registerCurrentTokenWithRetryBlocking(appContext, token)
            if (registerResponse == null) {
                enqueueRegistration(appContext, token, "foreground_refresh_failed")
                Log.w(TAG, "Resume token refresh could not be synced; will retry later")
                return@thread
            }

            saveLong(appContext, KEY_LAST_REGISTRATION_REFRESH_AT, System.currentTimeMillis())
            syncNotificationChannelsBlocking(appContext, force = true)
            flushPendingWorkBlocking(appContext)
        }
    }

    private fun syncNotificationChannelsIfNeeded(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        val platform = getStoredPlatform(appContext)
        if (platform != "android") {
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val lastSyncAt = getPrefs(appContext).getLong(KEY_LAST_CHANNEL_SYNC_AT, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < CHANNEL_SYNC_INTERVAL_MS) {
            return
        }

        thread {
            syncNotificationChannelsBlocking(appContext, force)
        }
    }

    private fun syncNotificationChannelsBlocking(context: Context, force: Boolean = false): Boolean {
        val appContext = context.applicationContext
        val appId = getStoredAppId(appContext)
        val platform = getStoredPlatform(appContext)

        if (platform != "android") {
            return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            saveLong(appContext, KEY_LAST_CHANNEL_SYNC_AT, System.currentTimeMillis())
            return true
        }

        if (appId.isNullOrBlank()) {
            Log.w(TAG, "Channel sync skipped because appId is missing")
            return false
        }

        val lastSyncAt = getPrefs(appContext).getLong(KEY_LAST_CHANNEL_SYNC_AT, 0L)
        val now = System.currentTimeMillis()
        if (!force && now - lastSyncAt < CHANNEL_SYNC_INTERVAL_MS) {
            return true
        }

        val response = createApiClient(appContext).syncNotificationChannelsBlocking(
            appId = appId,
            platform = platform
        )

        if (response == null) {
            Log.w(TAG, "Notification channel sync failed")
            return false
        }

        val activeChannelIds = mutableSetOf<String>()
        response.data.channels.forEach { remoteChannel ->
            val channelId = remoteChannel.channelId.trim()
            val channelName = remoteChannel.name.trim()

            if (channelId.isBlank() || channelName.isBlank()) {
                return@forEach
            }

            val created = createNotificationChannel(
                appContext,
                remoteChannel.toLocalChannelConfig()
            )

            if (created) {
                activeChannelIds.add(channelId)
            }
        }

        removeDeletedSyncedChannels(appContext, activeChannelIds)
        saveSyncedChannelIds(appContext, activeChannelIds)
        saveLong(appContext, KEY_LAST_CHANNEL_SYNC_AT, System.currentTimeMillis())
        Log.d(TAG, "Notification channel sync completed with ${activeChannelIds.size} channel(s)")
        return true
    }

    private fun RemoteNotificationChannel.toLocalChannelConfig(): SendrealmNotificationChannelConfig {
        return SendrealmNotificationChannelConfig(
            id = channelId.trim(),
            name = name.trim(),
            description = description,
            importance = resolveChannelImportance(importance),
            soundName = sound,
            enableVibration = vibration ?: true,
            enableLights = !ledColor.isNullOrBlank(),
            lightColor = parseColor(ledColor),
            lockscreenVisibility = resolveLockscreenVisibility(lockscreenVisibility)
        )
    }

    private fun removeDeletedSyncedChannels(context: Context, activeChannelIds: Set<String>) {
        val previousChannelIds = getSyncedChannelIds(context)
        val deletedChannelIds = previousChannelIds.filter { previousId ->
            previousId !in activeChannelIds
        }

        deletedChannelIds.forEach { channelId ->
            deleteNotificationChannel(context, channelId)
        }
    }

    private fun registerCurrentTokenWithRetryBlocking(
        context: Context,
        token: String,
        idempotencyKey: String? = null
    ): com.sendrealm.sdk.network.RegisterDeviceResponse? {
        repeat(REGISTRATION_RETRY_COUNT) { attemptIndex ->
            val response = registerCurrentTokenBlocking(context, token, idempotencyKey)
            if (response != null) {
                return response
            }

            if (attemptIndex < REGISTRATION_RETRY_COUNT - 1) {
                val delayMs = 1000L * (attemptIndex + 1)
                Log.w(
                    TAG,
                    "registerDevice failed on attempt ${attemptIndex + 1}; retrying in ${delayMs}ms"
                )
                Thread.sleep(delayMs)
            }
        }

        return null
    }

    private fun registerCurrentTokenBlocking(
        context: Context,
        token: String,
        idempotencyKeyOverride: String? = null
    ): com.sendrealm.sdk.network.RegisterDeviceResponse? {
        val appId = getStoredAppId(context)
        val deviceId = getStoredDeviceId(context)

        if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            Log.w(TAG, "registerCurrentTokenBlocking skipped because appId or deviceId is missing")
            return null
        }

        val apiClient = createApiClient(context)
        externalUserId = getStoredExternalUserId(context)
        userEmail = getStoredUserEmail(context)

        val response = apiClient.registerDeviceBlocking(
            appId = appId,
            registrationId = token,
            platform = getStoredPlatform(context),
            environment = getStoredEnvironment(context),
            deviceId = deviceId,
            apnsDeviceToken = null,
            userExternalId = externalUserId,
            userEmail = userEmail,
            appVersion = getAppVersion(context),
            sdkVersion = SDK_VERSION,
            osVersion = getOsVersion(),
            deviceLocale = getDeviceLocale(),
            timezone = getTimezoneId(),
            androidPackageName = context.packageName,
            deviceModel = getDeviceModel(),
            apiUrlSource = getStoredApiUrlSource(context),
            permissionStatus = getPermissionStatus(context),
            subscribed = isSubscribed(context),
            idempotencyKey = idempotencyKeyOverride ?: idempotencyKey("register")
        )

        if (response != null) {
            updateSubscribedState(context, true)
            saveOperationResult(context, KEY_LAST_REGISTER_RESULT, true)
        } else {
            saveOperationResult(context, KEY_LAST_REGISTER_RESULT, false, "Failed to register device with push API")
            saveSdkError(context, "E_REGISTER_FAILED", "Failed to register device with push API")
        }

        return response
    }

    private fun updateSubscriptionStateBlocking(
        context: Context,
        subscribed: Boolean,
        registrationId: String?,
        idempotencyKeyOverride: String? = null
    ): Boolean {
        val appId = getStoredAppId(context)
        val deviceId = getStoredDeviceId(context)

        if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            Log.w(TAG, "updateSubscriptionStateBlocking skipped because appId or deviceId is missing")
            return false
        }

        val success = createApiClient(context).updateSubscriptionBlocking(
            appId = appId,
            deviceId = deviceId,
            platform = getStoredPlatform(context),
            environment = getStoredEnvironment(context),
            subscribed = subscribed,
            registrationId = registrationId,
            apnsDeviceToken = null,
            appVersion = getAppVersion(context),
            sdkVersion = SDK_VERSION,
            osVersion = getOsVersion(),
            deviceLocale = getDeviceLocale(),
            timezone = getTimezoneId(),
            permissionStatus = getPermissionStatus(context),
            idempotencyKey = idempotencyKeyOverride ?: idempotencyKey(if (subscribed) "opt_in" else "opt_out")
        )

        if (success) {
            updateSubscribedState(context, subscribed)
        }

        return success
    }

    private fun createApiClient(context: Context, overrideBaseUrl: String? = null): ApiClient {
        return ApiClient(resolveBaseUrl(context, overrideBaseUrl))
    }

    private fun resolveBaseUrl(context: Context, overrideBaseUrl: String? = null): String {
        val normalizedOverride = normalize(overrideBaseUrl)
        if (normalizedOverride != null) {
            return normalizedOverride.trimEnd('/')
        }

        return getPrefs(context).getString(KEY_BASE_URL, null)?.trimEnd('/')
            ?: DEFAULT_BASE_URL
    }

    private fun persistConfig(context: Context, appId: String, config: SendrealmConfig) {
        val prefs = getPrefs(context)
        val normalizedExternalUserId = normalize(config.externalUserId)
        val normalizedUserEmail = normalize(config.userEmail)?.lowercase(Locale.US)
        val normalizedBaseUrl = normalize(config.baseUrl)?.trimEnd('/')

        prefs.edit {
            putString(KEY_APP_ID, appId)
            putString(KEY_PLATFORM, normalize(config.platform) ?: "android")
            putString(KEY_ENVIRONMENT, normalizeEnvironment(config.environment))

            if (normalizedBaseUrl != null) {
                putString(KEY_BASE_URL, normalizedBaseUrl)
                putString(KEY_API_URL_SOURCE, "options")
            } else if (!prefs.contains(KEY_API_URL_SOURCE)) {
                putString(KEY_API_URL_SOURCE, "default")
            }

            if (config.externalUserId != null) {
                putString(KEY_EXTERNAL_USER_ID, normalizedExternalUserId)
            }

            if (config.userEmail != null) {
                putString(KEY_USER_EMAIL, normalizedUserEmail)
            }
        }

        externalUserId = getStoredExternalUserId(context)
        userEmail = getStoredUserEmail(context)
    }

    private fun ensureFirebaseIsReady(
        context: Context,
        fcmApp: com.sendrealm.sdk.network.FcmApp
    ): Boolean {
        val existingApp = try {
            FirebaseApp.getInstance()
        } catch (_: IllegalStateException) {
            null
        }

        if (existingApp != null) {
            val options = existingApp.options
            val sameProject =
                options.applicationId == fcmApp.applicationId &&
                    options.projectId == fcmApp.projectId &&
                    options.gcmSenderId == fcmApp.senderId

            if (!sameProject) {
                Log.w(
                    TAG,
                    "Default FirebaseApp already exists with a different config. " +
                        "The host app configuration will be used for token generation."
                )
            }

            return true
        }

        val options = FirebaseOptions.Builder()
            .setApplicationId(fcmApp.applicationId)
            .setApiKey(fcmApp.apiKey)
            .setProjectId(fcmApp.projectId)
            .setGcmSenderId(fcmApp.senderId)
            .build()

        FirebaseApp.initializeApp(context, options)

        return true
    }

    private fun awaitFcmToken(): String? {
        testingFcmTokenProvider?.let { provider ->
            return provider()
        }

        repeat(3) { attemptIndex ->
            val latch = CountDownLatch(1)
            var token: String? = null

            Handler(Looper.getMainLooper()).post {
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            token = task.result
                            Log.d(TAG, "FCM registration token acquired")
                        } else {
                            Log.e(
                                TAG,
                                "Failed to get FCM registration token on attempt ${attemptIndex + 1}",
                                task.exception
                            )
                        }

                        latch.countDown()
                    }
                } catch (error: Exception) {
                    Log.e(TAG, "Failed to request FCM registration token on attempt ${attemptIndex + 1}", error)
                    latch.countDown()
                }
            }

            if (latch.await(15, TimeUnit.SECONDS) && !token.isNullOrBlank()) {
                return token
            }

            if (attemptIndex < 2) {
                Thread.sleep(2000)
            }
        }

        return null
    }

    private fun awaitFreshFcmToken(): String? {
        testingFcmTokenProvider?.let {
            if (testingFcmDeleteResult) {
                Log.d(TAG, "Cached FCM token deleted before init refresh")
            } else {
                Log.w(TAG, "Failed to delete cached FCM token before init refresh; trying current token")
            }
            return awaitFcmToken()
        }

        val deleteLatch = CountDownLatch(1)
        var deleted = false

        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task ->
                deleted = task.isSuccessful
                if (!task.isSuccessful) {
                    Log.w(
                        TAG,
                        "Failed to delete cached FCM token before init refresh; trying current token",
                        task.exception
                    )
                }

                deleteLatch.countDown()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to request cached FCM token deletion before init refresh; trying current token", error)
            deleteLatch.countDown()
        }

        if (!deleteLatch.await(15, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out deleting cached FCM token before init refresh; trying current token")
        } else if (deleted) {
            Log.d(TAG, "Cached FCM token deleted before init refresh")
        }

        return awaitFcmToken()
    }

    private fun maybeRequestNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            notifyPermissionChangeIfNeeded(
                context.applicationContext,
                force = true,
                trackServerEvent = true
            )
            return
        }

        if (hasNotificationPermission(context)) {
            notifyPermissionChangeIfNeeded(
                context.applicationContext,
                force = true,
                trackServerEvent = true
            )
            return
        }

        if (context !is Activity) {
            Log.d(TAG, "Notification permission not requested because context is not an Activity")
            return
        }

        Handler(Looper.getMainLooper()).post {
            context.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun registerLifecycleCallbacks(context: Context) {
        val application = context.applicationContext as? Application ?: return

        synchronized(this) {
            if (lifecycleCallbacksRegistered) {
                return
            }

            lifecycleCallbacksRegistered = true
        }

        application.registerActivityLifecycleCallbacks(createLifecycleCallbacks())
    }

    private fun createLifecycleCallbacks(): Application.ActivityLifecycleCallbacks {
        return object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivities += 1
                isForeground = startedActivities > 0
                notifyPermissionChangeIfNeeded(activity.applicationContext)
            }

            override fun onActivityResumed(activity: Activity) {
                isForeground = startedActivities > 0
                notifyPermissionChangeIfNeeded(activity.applicationContext)
                refreshRegistrationIfNeeded(activity.applicationContext)
                syncNotificationChannelsIfNeeded(activity.applicationContext)
                thread {
                    flushPendingWorkBlocking(activity.applicationContext)
                }
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivities = max(0, startedActivities - 1)
                isForeground = startedActivities > 0
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    }

    private fun notifyPermissionChangeIfNeeded(
        context: Context,
        force: Boolean = false,
        trackServerEvent: Boolean = true
    ) {
        val current = hasNotificationPermission(context)
        val previous = getStoredPermissionGranted(context)

        if (!force && previous != null && previous == current) {
            return
        }

        saveBoolean(context, KEY_PERMISSION_GRANTED, current)
        permissionObservers.forEach { it.onPermissionChanged(current) }

        if (trackServerEvent && previous != null && previous != current) {
            val eventType = if (current) "permission_granted" else "permission_denied"
            trackDeviceEventAsync(eventType)
        }
    }

    private fun updateSubscribedState(context: Context, subscribed: Boolean) {
        val previous = getStoredSubscribed(context)
        saveBoolean(context, KEY_SUBSCRIBED, subscribed)

        if (previous == null || previous != subscribed) {
            subscriptionObservers.forEach { it.onSubscriptionChanged(subscribed) }
        }
    }

    private fun isSubscribed(context: Context): Boolean {
        return getStoredSubscribed(context) ?: false
    }

    private fun trackDeviceEventAsync(eventType: String) {
        trackDeviceEventAsync(eventType, null)
    }

    private fun trackDeviceEventAsync(eventType: String, properties: Map<String, Any?>?) {
        val appContext = applicationContext ?: return

        thread {
            val success = trackDeviceEventBlocking(appContext, eventType, properties = properties)
            if (!success) {
                enqueueEvent(appContext, eventType, properties = properties)
            }
        }
    }

    private fun trackDeviceEventBlocking(
        context: Context,
        eventType: String,
        notificationId: String? = null,
        properties: Map<String, Any?>? = null,
        idempotencyKeyOverride: String? = null
    ): Boolean {
        val appId = getStoredAppId(context)
        val deviceId = getStoredDeviceId(context)

        if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            return false
        }

        return createApiClient(context).trackEventBlocking(
            appId = appId,
            deviceId = deviceId,
            platform = getStoredPlatform(context),
            eventType = eventType,
            notificationId = notificationId,
            properties = properties,
            appVersion = getAppVersion(context),
            sdkVersion = SDK_VERSION,
            osVersion = getOsVersion(),
            deviceLocale = getDeviceLocale(),
            timezone = getTimezoneId(),
            idempotencyKey = idempotencyKeyOverride ?: idempotencyKey(eventType)
        )
    }

    private fun trackNotificationEventAsync(
        eventType: String,
        notificationId: String,
        properties: Map<String, Any?>? = null
    ) {
        val appContext = applicationContext ?: return

        thread {
            val success = trackDeviceEventBlocking(
                appContext,
                eventType,
                notificationId,
                properties
            )
            if (!success) {
                enqueueEvent(appContext, eventType, notificationId, properties)
            }
        }
    }

    private fun isDuplicateOpen(
        context: Context,
        result: SendrealmNotificationOpenResult
    ): Boolean {
        val key = result.clickId
            ?: result.notificationId
            ?: result.rawPayload
            ?: return false
        val now = System.currentTimeMillis()
        val recent = getRecentOpenKeys(context)
            .filterValues { now - it < 5 * 60 * 1000L }
            .toMutableMap()

        if (recent.containsKey(key)) {
            saveRecentOpenKeys(context, recent)
            return true
        }

        recent[key] = now
        saveRecentOpenKeys(context, recent)
        return false
    }

    private fun enqueueEvent(
        context: Context,
        eventType: String,
        notificationId: String? = null,
        properties: Map<String, Any?>? = null
    ) {
        if (eventType.isBlank()) {
            return
        }

        synchronized(pendingQueueLock) {
            val pendingEvents = getPendingEvents(context)
            pendingEvents.add(
                QueuedEvent(
                    eventType = eventType,
                    notificationId = notificationId,
                    properties = properties,
                    idempotencyKey = idempotencyKey(eventType),
                    queuedAt = System.currentTimeMillis()
                )
            )

            savePendingEvents(context, pendingEvents.takeLast(100).toMutableList())
        }
    }

    private fun enqueueTags(context: Context, tags: Map<String, Any?>) {
        if (tags.isEmpty()) {
            return
        }

        val pendingTags = getPendingTags(context)
        pendingTags.putAll(tags)
        savePendingTags(context, pendingTags)
    }

    private fun enqueueRegistration(context: Context, registrationId: String, reason: String) {
        if (registrationId.isBlank()) {
            return
        }

        val registrations = getPendingRegistrations(context)
            .filterNot { it.registrationId == registrationId }
            .toMutableList()
        registrations.add(
            QueuedRegistration(
                registrationId = registrationId,
                reason = reason,
                idempotencyKey = idempotencyKey("register")
            )
        )
        savePendingRegistrations(context, registrations.takeLast(100).toMutableList())
    }

    private fun enqueueSubscription(
        context: Context,
        subscribed: Boolean,
        registrationId: String?,
        reason: String
    ) {
        val subscriptions = getPendingSubscriptions(context)
        subscriptions.add(
            QueuedSubscription(
                subscribed = subscribed,
                registrationId = registrationId,
                reason = reason,
                idempotencyKey = idempotencyKey(if (subscribed) "opt_in" else "opt_out")
            )
        )
        savePendingSubscriptions(context, subscriptions.takeLast(100).toMutableList())
    }

    private fun flushPendingWorkBlocking(context: Context) {
        flushPendingRegistrationsBlocking(context)
        flushPendingSubscriptionsBlocking(context)
        flushPendingTagsBlocking(context)
        flushPendingEventsBlocking(context)
    }

    private fun flushPendingRegistrationsBlocking(context: Context) {
        val registrations = getPendingRegistrations(context)
        if (registrations.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val remaining = mutableListOf<QueuedRegistration>()
        registrations.forEach { registration ->
            if (registration.nextAttemptAt > now) {
                remaining.add(registration)
                return@forEach
            }

            val response = registerCurrentTokenWithRetryBlocking(
                context,
                registration.registrationId,
                registration.idempotencyKey
            )
            if (response == null) {
                remaining.add(
                    registration.copy(
                        retryCount = registration.retryCount + 1,
                        nextAttemptAt = now + retryDelayMs(registration.retryCount + 1)
                    )
                )
            }
        }

        savePendingRegistrations(context, remaining.takeLast(100).toMutableList())
    }

    private fun flushPendingSubscriptionsBlocking(context: Context) {
        val subscriptions = getPendingSubscriptions(context)
        if (subscriptions.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val remaining = mutableListOf<QueuedSubscription>()
        subscriptions.forEach { subscription ->
            if (subscription.nextAttemptAt > now) {
                remaining.add(subscription)
                return@forEach
            }

            val success = updateSubscriptionStateBlocking(
                context = context,
                subscribed = subscription.subscribed,
                registrationId = subscription.registrationId,
                idempotencyKeyOverride = subscription.idempotencyKey
            )
            if (!success) {
                remaining.add(
                    subscription.copy(
                        retryCount = subscription.retryCount + 1,
                        nextAttemptAt = now + retryDelayMs(subscription.retryCount + 1)
                    )
                )
            }
        }

        savePendingSubscriptions(context, remaining.takeLast(100).toMutableList())
    }

    private fun flushPendingTagsBlocking(context: Context) {
        val pendingTags = getPendingTags(context)
        if (pendingTags.isEmpty()) {
            return
        }

        val appId = getStoredAppId(context)
        val deviceId = getStoredDeviceId(context)
        if (appId.isNullOrBlank() || deviceId.isNullOrBlank()) {
            return
        }

        val success = createApiClient(context).updateTagsBlocking(
            appId = appId,
            deviceId = deviceId,
            platform = getStoredPlatform(context),
            tags = pendingTags
        )

        if (success) {
            savePendingTags(context, mutableMapOf())
        }
    }

    private fun flushPendingEventsBlocking(context: Context) {
        val pendingEvents = getPendingEvents(context)
        if (pendingEvents.isEmpty()) {
            return
        }

        val remainingEvents = mutableListOf<QueuedEvent>()
        pendingEvents.forEach { event ->
            val eventIdempotencyKey = event.idempotencyKey ?: idempotencyKey(event.eventType)
            val success = trackDeviceEventBlocking(
                context = context,
                eventType = event.eventType,
                notificationId = event.notificationId,
                properties = event.properties,
                idempotencyKeyOverride = eventIdempotencyKey
            )

            if (!success) {
                remainingEvents.add(event.copy(idempotencyKey = eventIdempotencyKey))
            }
        }

        savePendingEvents(context, remainingEvents)
    }

    private fun getAppVersion(context: Context): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read app version", e)
            null
        }
    }

    private fun resolveRawSoundUri(context: Context, soundName: String?): Uri? {
        val normalizedSoundName = normalize(soundName) ?: return null
        if (normalizedSoundName.equals("default", ignoreCase = true)) {
            return null
        }

        val rawResourceId = context.resources.getIdentifier(
            normalizedSoundName.removeSuffix(".mp3").removeSuffix(".wav"),
            "raw",
            context.packageName
        )

        return if (rawResourceId != 0) {
            Uri.parse("android.resource://${context.packageName}/$rawResourceId")
        } else {
            Uri.parse(normalizedSoundName)
        }
    }

    private fun resolveChannelImportance(importance: String?): Int {
        return when (importance?.lowercase(Locale.US)) {
            "none" -> NotificationManager.IMPORTANCE_NONE
            "min" -> NotificationManager.IMPORTANCE_MIN
            "low" -> NotificationManager.IMPORTANCE_LOW
            "default" -> NotificationManager.IMPORTANCE_DEFAULT
            "max", "high" -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_HIGH
        }
    }

    private fun resolveLockscreenVisibility(visibility: String?): Int {
        return when (visibility?.lowercase(Locale.US)) {
            "private" -> android.app.Notification.VISIBILITY_PRIVATE
            "secret" -> android.app.Notification.VISIBILITY_SECRET
            else -> android.app.Notification.VISIBILITY_PUBLIC
        }
    }

    private fun parseColor(color: String?): Int? {
        val normalizedColor = normalize(color) ?: return null
        return try {
            Color.parseColor(normalizedColor)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun buildExternalLaunchIntent(
        notificationId: String?,
        deliveryId: String?,
        clickId: String?,
        actionIdentifier: String?,
        launchUrl: String?,
        rawPayload: String?,
        tracked: Boolean
    ): Intent? {
        val normalizedLaunchUrl = normalizeLaunchUrl(launchUrl) ?: return null
        val uri = Uri.parse(normalizedLaunchUrl)

        return Intent(Intent.ACTION_VIEW, uri).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_DELIVERY_ID, deliveryId)
            putExtra(EXTRA_CLICK_ID, clickId)
            putExtra(EXTRA_ACTION_ID, actionIdentifier)
            putExtra(EXTRA_LAUNCH_URL, normalizedLaunchUrl)
            putExtra(EXTRA_PAYLOAD, rawPayload)
            putExtra(EXTRA_TRACKED, tracked)
            putExtra(EXTRA_HANDLED, false)
        }
    }

    private fun buildAppLaunchIntent(
        context: Context,
        notificationId: String?,
        deliveryId: String?,
        clickId: String?,
        actionIdentifier: String?,
        launchUrl: String?,
        rawPayload: String?,
        tracked: Boolean
    ): Intent? {
        val normalizedLaunchUrl = normalizeLaunchUrl(launchUrl)
        return context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_DELIVERY_ID, deliveryId)
            putExtra(EXTRA_CLICK_ID, clickId)
            putExtra(EXTRA_ACTION_ID, actionIdentifier)
            putExtra(EXTRA_LAUNCH_URL, normalizedLaunchUrl)
            putExtra(EXTRA_PAYLOAD, rawPayload)
            putExtra(EXTRA_TRACKED, tracked)
            putExtra(EXTRA_HANDLED, false)
        }
    }

    private fun normalizeLaunchUrl(launchUrl: String?): String? {
        val trimmedLaunchUrl = launchUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val parsedUri = Uri.parse(trimmedLaunchUrl)

        if (!parsedUri.scheme.isNullOrBlank()) {
            return trimmedLaunchUrl
        }

        return if (trimmedLaunchUrl.contains(".") && !trimmedLaunchUrl.contains(" ")) {
            "https://$trimmedLaunchUrl"
        } else {
            trimmedLaunchUrl
        }
    }

    private fun getOsVersion(): String {
        return Build.VERSION.RELEASE ?: "unknown"
    }

    private fun getDeviceModel(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "unknown" }
    }

    private fun getDeviceLocale(): String {
        return Locale.getDefault().toLanguageTag()
    }

    private fun getTimezoneId(): String {
        return TimeZone.getDefault().id
    }

    private fun retryDelayMs(retryCount: Int): Long {
        return (1000L * (1 shl retryCount.coerceIn(0, 8))).coerceAtMost(5 * 60 * 1000L)
    }

    private fun idempotencyKey(prefix: String): String {
        return "${prefix.lowercase(Locale.US)}-${java.util.UUID.randomUUID()}"
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getStoredAppId(context: Context): String? =
        getPrefs(context).getString(KEY_APP_ID, null)

    private fun getStoredDeviceId(context: Context): String? =
        getPrefs(context).getString(KEY_DEVICE_ID, null)

    private fun getStoredPlatform(context: Context): String =
        getPrefs(context).getString(KEY_PLATFORM, null) ?: "android"

    private fun getStoredEnvironment(context: Context): String =
        normalizeEnvironment(getPrefs(context).getString(KEY_ENVIRONMENT, null))

    private fun getStoredExternalUserId(context: Context): String? =
        getPrefs(context).getString(KEY_EXTERNAL_USER_ID, null)

    private fun getStoredUserEmail(context: Context): String? =
        getPrefs(context).getString(KEY_USER_EMAIL, null)

    private fun getStoredRegistrationToken(context: Context): String? =
        getPrefs(context).getString(KEY_LAST_TOKEN, null)

    private fun getStoredSubscribed(context: Context): Boolean? {
        val prefs = getPrefs(context)
        return if (prefs.contains(KEY_SUBSCRIBED)) {
            prefs.getBoolean(KEY_SUBSCRIBED, false)
        } else {
            null
        }
    }

    private fun getPendingEvents(context: Context): MutableList<QueuedEvent> {
        val rawEvents = getPrefs(context).getString(KEY_PENDING_EVENTS, null)
        if (rawEvents.isNullOrBlank()) {
            return mutableListOf()
        }

        return try {
            gson.fromJson<MutableList<QueuedEvent>>(rawEvents, queuedEventsType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingEvents(context: Context, events: MutableList<QueuedEvent>) {
        getPrefs(context).edit {
            if (events.isEmpty()) {
                remove(KEY_PENDING_EVENTS)
            } else {
                putString(KEY_PENDING_EVENTS, gson.toJson(events))
            }
        }
    }

    private fun getPendingTags(context: Context): MutableMap<String, Any?> {
        val rawTags = getPrefs(context).getString(KEY_PENDING_TAGS, null)
        if (rawTags.isNullOrBlank()) {
            return mutableMapOf()
        }

        return try {
            gson.fromJson<MutableMap<String, Any?>>(rawTags, queuedTagsType)
                ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun savePendingTags(context: Context, tags: MutableMap<String, Any?>) {
        getPrefs(context).edit {
            if (tags.isEmpty()) {
                remove(KEY_PENDING_TAGS)
            } else {
                putString(KEY_PENDING_TAGS, gsonWithNulls.toJson(tags))
            }
        }
    }

    private fun getPendingRegistrations(context: Context): MutableList<QueuedRegistration> {
        val rawRegistrations = getPrefs(context).getString(KEY_PENDING_REGISTRATIONS, null)
        if (rawRegistrations.isNullOrBlank()) {
            return mutableListOf()
        }

        return try {
            gson.fromJson<MutableList<QueuedRegistration>>(rawRegistrations, queuedRegistrationsType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingRegistrations(
        context: Context,
        registrations: MutableList<QueuedRegistration>
    ) {
        getPrefs(context).edit {
            if (registrations.isEmpty()) {
                remove(KEY_PENDING_REGISTRATIONS)
            } else {
                putString(KEY_PENDING_REGISTRATIONS, gson.toJson(registrations))
            }
        }
    }

    private fun getPendingSubscriptions(context: Context): MutableList<QueuedSubscription> {
        val rawSubscriptions = getPrefs(context).getString(KEY_PENDING_SUBSCRIPTIONS, null)
        if (rawSubscriptions.isNullOrBlank()) {
            return mutableListOf()
        }

        return try {
            gson.fromJson<MutableList<QueuedSubscription>>(rawSubscriptions, queuedSubscriptionsType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun savePendingSubscriptions(
        context: Context,
        subscriptions: MutableList<QueuedSubscription>
    ) {
        getPrefs(context).edit {
            if (subscriptions.isEmpty()) {
                remove(KEY_PENDING_SUBSCRIPTIONS)
            } else {
                putString(KEY_PENDING_SUBSCRIPTIONS, gson.toJson(subscriptions))
            }
        }
    }

    private fun getSyncedChannelIds(context: Context): MutableList<String> {
        val rawChannelIds = getPrefs(context).getString(KEY_SYNCED_CHANNEL_IDS, null)
        if (rawChannelIds.isNullOrBlank()) {
            return mutableListOf()
        }

        return try {
            gson.fromJson<MutableList<String>>(rawChannelIds, syncedChannelIdsType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun saveSyncedChannelIds(context: Context, channelIds: Set<String>) {
        getPrefs(context).edit {
            if (channelIds.isEmpty()) {
                remove(KEY_SYNCED_CHANNEL_IDS)
            } else {
                putString(KEY_SYNCED_CHANNEL_IDS, gson.toJson(channelIds.toList()))
            }
        }
    }

    private fun getRecentOpenKeys(context: Context): MutableMap<String, Long> {
        val rawKeys = getPrefs(context).getString(KEY_RECENT_OPEN_KEYS, null)
        if (rawKeys.isNullOrBlank()) {
            return mutableMapOf()
        }

        return try {
            gson.fromJson<MutableMap<String, Long>>(rawKeys, recentOpenKeysType)
                ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveRecentOpenKeys(context: Context, keys: MutableMap<String, Long>) {
        val trimmed = keys.entries
            .sortedByDescending { it.value }
            .take(50)
            .associate { it.key to it.value }

        getPrefs(context).edit {
            if (trimmed.isEmpty()) {
                remove(KEY_RECENT_OPEN_KEYS)
            } else {
                putString(KEY_RECENT_OPEN_KEYS, gson.toJson(trimmed))
            }
        }
    }

    private fun getStoredPermissionGranted(context: Context): Boolean? {
        val prefs = getPrefs(context)
        return if (prefs.contains(KEY_PERMISSION_GRANTED)) {
            prefs.getBoolean(KEY_PERMISSION_GRANTED, false)
        } else {
            null
        }
    }

    private fun getStoredApiUrlSource(context: Context): String =
        getPrefs(context).getString(KEY_API_URL_SOURCE, null) ?: "default"

    private fun getQueueCounts(context: Context): SendrealmQueueCounts {
        return SendrealmQueueCounts(
            events = getPendingEvents(context).size,
            tags = getPendingTags(context).size,
            registrations = getPendingRegistrations(context).size,
            subscriptions = getPendingSubscriptions(context).size
        )
    }

    private fun getStoredOperationResult(
        context: Context,
        key: String
    ): SendrealmOperationResult? {
        val raw = getPrefs(context).getString(key, null) ?: return null
        return try {
            gson.fromJson<SendrealmOperationResult>(raw, operationResultType)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveOperationResult(
        context: Context,
        key: String,
        success: Boolean,
        message: String? = null
    ) {
        getPrefs(context).edit {
            putString(key, gson.toJson(SendrealmOperationResult(success, message)))
        }
    }

    private fun getStoredSdkError(context: Context): SendrealmSdkError? {
        val raw = getPrefs(context).getString(KEY_LAST_SDK_ERROR, null) ?: return null
        return try {
            gson.fromJson<SendrealmSdkError>(raw, sdkErrorType)
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSdkError(context: Context, code: String, message: String) {
        getPrefs(context).edit {
            putString(KEY_LAST_SDK_ERROR, gson.toJson(SendrealmSdkError(code, message)))
        }
    }

    private fun saveString(context: Context, key: String, value: String?) {
        getPrefs(context).edit {
            putString(key, value)
        }
    }

    private fun saveBoolean(context: Context, key: String, value: Boolean) {
        getPrefs(context).edit {
            putBoolean(key, value)
        }
    }

    private fun saveInt(context: Context, key: String, value: Int) {
        getPrefs(context).edit {
            putInt(key, value)
        }
    }

    private fun saveLong(context: Context, key: String, value: Long) {
        getPrefs(context).edit {
            putLong(key, value)
        }
    }

    private fun postResult(callback: ((String?) -> Unit)?, token: String?) {
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(token)
        }
    }

    private fun postBooleanResult(callback: ((Boolean) -> Unit)?, success: Boolean) {
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(success)
        }
    }

    private fun parsePayload(rawPayload: String?): SendrealmPayload? {
        if (rawPayload.isNullOrBlank()) {
            return null
        }

        return try {
            gson.fromJson(rawPayload, SendrealmPayload::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun normalize(value: String?): String? {
        val trimmed = value?.trim()
        return if (trimmed.isNullOrEmpty()) null else trimmed
    }

    private fun normalizeEnvironment(value: String?): String {
        return if (normalize(value) == "development") "development" else "production"
    }

    private fun normalizeTagValue(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> normalize(value)
            is Number -> value
            is Boolean -> value
            else -> normalize(value.toString())
        }
    }
}

private data class QueuedEvent(
    val eventType: String,
    val notificationId: String? = null,
    val properties: Map<String, Any?>? = null,
    val idempotencyKey: String? = null,
    val queuedAt: Long = System.currentTimeMillis()
)

private data class QueuedRegistration(
    val registrationId: String,
    val reason: String,
    val idempotencyKey: String,
    val retryCount: Int = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    val nextAttemptAt: Long = System.currentTimeMillis()
)

private data class QueuedSubscription(
    val subscribed: Boolean,
    val registrationId: String? = null,
    val reason: String,
    val idempotencyKey: String,
    val retryCount: Int = 0,
    val queuedAt: Long = System.currentTimeMillis(),
    val nextAttemptAt: Long = System.currentTimeMillis()
)
