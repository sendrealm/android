package com.sendrealm.sdk

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendrealmPublicApiTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        Sendrealm.testingResetState(context)
    }

    @After
    fun tearDown() {
        Sendrealm.testingResetState(context)
        server.shutdown()
    }

    @Test
    fun stateDiagnosticsIdentityForegroundBadgeAndSettingsApisReflectStoredState() {
        val emptyState = Sendrealm.getState()
        assertFalse(emptyState.initialized)
        assertFalse(emptyState.registered)
        assertEquals("missing", emptyState.tokenStatus)
        assertNull(Sendrealm.getDeviceId())
        assertFalse(Sendrealm.isSubscribed())

        Sendrealm.testingSetState(
            context = context,
            appId = "app_123",
            baseUrl = server.url("/push///").toString(),
            platform = "android",
            deviceId = "device_123",
            registrationToken = "fcm-token",
            externalUserId = " user_123 ",
            userEmail = "PERSON@EXAMPLE.COM",
            subscribed = true,
            permissionGranted = false
        )
        val application = context as Application
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val permissionEvents = mutableListOf<Boolean>()
        val subscriptionEvents = mutableListOf<Boolean>()
        val permissionObserver = SendrealmPermissionObserver { permissionEvents.add(it) }
        val subscriptionObserver = SendrealmSubscriptionObserver { subscriptionEvents.add(it) }
        Sendrealm.addPermissionObserver(permissionObserver)
        Sendrealm.addSubscriptionObserver(subscriptionObserver)

        val state = Sendrealm.getState(context)
        val diagnostics = Sendrealm.getDiagnostics(context)

        assertTrue(state.initialized)
        assertTrue(state.registered)
        assertTrue(state.permissionGranted)
        assertTrue(state.subscribed)
        assertEquals("registered", state.tokenStatus)
        assertEquals("device_123", Sendrealm.getDeviceId())
        assertEquals("user_123", state.externalUserId)
        assertEquals("person@example.com", state.userEmail)
        assertEquals("authorized", diagnostics.permissionStatus)
        assertEquals("android", diagnostics.platform)
        assertEquals("0.1.0", diagnostics.sdkVersion)
        assertTrue(diagnostics.registrationTokenPresent)
        assertEquals(server.url("/push").toString().trimEnd('/'), diagnostics.apiUrl)
        assertEquals(0, diagnostics.queueCounts.events)
        assertEquals(listOf(true), permissionEvents)
        assertEquals(listOf(true), subscriptionEvents)

        assertTrue(
            Sendrealm.setForegroundPresentation(
                context,
                SendrealmForegroundPresentationOptions(display = false)
            )
        )
        assertFalse(Sendrealm.shouldDisplayForegroundNotification(context))

        assertTrue(Sendrealm.setBadgeCount(context, -10))
        assertTrue(Sendrealm.clearBadge(context))
        val broadcasts = shadowOf(application).broadcastIntents
        assertEquals("android.intent.action.BADGE_COUNT_UPDATE", broadcasts.first().action)
        assertEquals(0, broadcasts.first().getIntExtra("badge_count", -1))

        assertTrue(Sendrealm.openNotificationSettings(context))
        val settingsIntent = shadowOf(application).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, settingsIntent.action)
        assertEquals(context.packageName, settingsIntent.getStringExtra(Settings.EXTRA_APP_PACKAGE))

        Sendrealm.login(" next_user ", "NEXT@EXAMPLE.COM")
        assertEquals("next_user", Sendrealm.getState(context).externalUserId)
        assertEquals("next@example.com", Sendrealm.getState(context).userEmail)
        Sendrealm.logout()
        assertNull(Sendrealm.getState(context).externalUserId)
        assertNull(Sendrealm.getState(context).userEmail)

        Sendrealm.removePermissionObserver(permissionObserver)
        Sendrealm.removeSubscriptionObserver(subscriptionObserver)
    }

    @Test
    fun publicNetworkApisPostExpectedBodiesAndUpdateLocalState() {
        Sendrealm.testingSetState(
            context = context,
            baseUrl = server.url("/").toString(),
            deviceId = "device_123",
            registrationToken = "fcm-token",
            subscribed = true,
            permissionGranted = true
        )

        server.enqueue(status(200))
        assertTrue(
            awaitBooleanCallback { callback ->
                Sendrealm.registerLiveActivityToken(
                    token = "live-token",
                    activityId = "activity_123",
                    tokenType = "android_update",
                    activityType = "Delivery",
                    attributesType = "DeliveryAttributes",
                    callback = callback
                )
            }
        )
        var request = takeRequestBody()
        assertEquals("POST", request.method)
        assertEquals("/v1/live-activities/tokens", request.path)
        assertEquals("live-token", request.body["token"].asString)
        assertEquals("android_update", request.body["token_type"].asString)
        assertEquals("DeliveryAttributes", request.body["attributes_type"].asString)

        server.enqueue(status(200))
        assertTrue(
            awaitBooleanCallback { callback ->
                Sendrealm.deleteLiveActivityToken(
                    token = "live-token",
                    activityId = "activity_123",
                    tokenType = "android_update",
                    activityType = "Delivery",
                    attributesType = "DeliveryAttributes",
                    callback = callback
                )
            }
        )
        request = takeRequestBody()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/live-activities/tokens", request.path)
        assertEquals("activity_123", request.body["activity_id"].asString)

        server.enqueue(
            status(
                200,
                """
                {
                  "status": 200,
                  "data": {
                    "app_id": "app_123",
                    "platform": "android",
                    "synced_at": "2026-06-23T10:00:00.000Z",
                    "channels": [
                      {
                        "channel_id": "orders",
                        "name": "Orders",
                        "description": "Order updates",
                        "importance": "default",
                        "vibration": true,
                        "led_color": "#FF0000",
                        "lockscreen_visibility": "secret"
                      },
                      {
                        "channel_id": "   ",
                        "name": "Ignored"
                      }
                    ]
                  }
                }
                """.trimIndent()
            )
        )
        assertTrue(awaitBooleanCallback { callback -> Sendrealm.syncNotificationChannels(callback) })
        request = takeRequestBody()
        assertEquals("POST", request.method)
        assertEquals("/v1/notification-channels/sync", request.path)
        assertEquals("app_123", request.body["app_id"].asString)
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .getNotificationChannel("orders")
        assertEquals("Orders", channel.name.toString())
        assertEquals(Notification.VISIBILITY_SECRET, channel.lockscreenVisibility)

        server.enqueue(status(200))
        assertTrue(awaitBooleanCallback { callback -> Sendrealm.optOut(callback) })
        request = takeRequestBody()
        assertEquals("POST", request.method)
        assertEquals("/v1/subscription", request.path)
        assertFalse(request.body["subscribed"].asBoolean)
        assertFalse(Sendrealm.getState(context).subscribed)

        server.enqueue(status(500))
        assertTrue(awaitBooleanCallback { callback -> Sendrealm.addTag("plan", " pro ", callback) })
        request = takeRequestBody()
        assertEquals("/v1/tags", request.path)
        assertEquals("pro", request.body["tags"].asJsonObject["plan"].asString)
        assertEquals("pro", Sendrealm.testingQueuedTags(context)["plan"])

        server.enqueue(status(500))
        assertTrue(
            awaitBooleanCallback { callback ->
                Sendrealm.trackEvent(
                    "notification_action",
                    mapOf("action_id" to "view"),
                    callback
                )
            }
        )
        request = takeRequestBody()
        assertEquals("/v1/track", request.path)
        assertEquals("notification_action", request.body["event_type"].asString)
        assertEquals("notification_action", Sendrealm.testingQueuedEvents(context).last()["eventType"])
    }

    @Test
    fun clickActivityAndActionReceiverHandleOpenActionAndDismissPaths() {
        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        val rawPayload = """
            {
              "metadata": {
                "notification_id": "notif_receiver_1",
                "android_launch_url": "example.com/path"
              },
              "live_activity": {
                "activity_id": "live_receiver_1",
                "send_id": "send_receiver_1",
                "launch_url": "myapp://live"
              }
            }
        """.trimIndent()
        val openIntent = Intent(context, SendrealmNotificationClickActivity::class.java)
            .setAction(Sendrealm.ACTION_NOTIFICATION_ACTION)
            .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_receiver_1")
            .putExtra(Sendrealm.EXTRA_DELIVERY_ID, "delivery_receiver_1")
            .putExtra(Sendrealm.EXTRA_CLICK_ID, "click_receiver_1")
            .putExtra(Sendrealm.EXTRA_ACTION_ID, "details")
            .putExtra(Sendrealm.EXTRA_LAUNCH_URL, "example.com/path")
            .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)

        val controller = Robolectric.buildActivity(
            SendrealmNotificationClickActivity::class.java,
            openIntent
        ).create()
        assertTrue(controller.get().isFinishing)
        waitUntil {
            Sendrealm.testingQueuedEvents(context)
                .any { it["eventType"] == "notification_action" }
        }
        val launched = shadowOf(controller.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://example.com/path", launched.dataString)
        assertTrue(openIntent.getBooleanExtra("com.sendrealm.sdk.extra.HANDLED", false))

        controller.newIntent(
            Intent(context, SendrealmNotificationClickActivity::class.java)
                .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_receiver_1")
                .putExtra(Sendrealm.EXTRA_LAUNCH_URL, "myapp://replacement")
                .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        )

        val receiver = SendrealmNotificationActionReceiver()
        receiver.onReceive(context, null)
        receiver.onReceive(context, Intent("com.sendrealm.sdk.action.UNKNOWN"))
        receiver.onReceive(
            context,
            Intent(Sendrealm.ACTION_NOTIFICATION_OPEN)
                .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_receiver_open")
                .putExtra(Sendrealm.EXTRA_CLICK_ID, "click_receiver_open")
                .putExtra(Sendrealm.EXTRA_LAUNCH_URL, "example.com/open")
                .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        )
        assertEquals(
            "https://example.com/open",
            shadowOf(context as Application).nextStartedActivity.dataString
        )

        val actionEvents = mutableListOf<SendrealmNotificationActionEvent>()
        Sendrealm.addNotificationActionListener { actionEvents.add(it) }
        receiver.onReceive(
            context,
            Intent(Sendrealm.ACTION_NOTIFICATION_ACTION)
                .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_receiver_action")
                .putExtra(Sendrealm.EXTRA_CLICK_ID, "click_receiver_action")
                .putExtra(Sendrealm.EXTRA_ACTION_ID, "archive")
                .putExtra(Sendrealm.EXTRA_LAUNCH_URL, "example.com/action")
                .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        )
        assertEquals("archive", actionEvents.single().actionIdentifier)

        val dismissIntent = Intent(Sendrealm.ACTION_NOTIFICATION_DISMISS)
            .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_receiver_2")
            .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)
        receiver.onReceive(context, dismissIntent)
        waitUntil {
            val eventTypes = Sendrealm.testingQueuedEvents(context).map { it["eventType"] }
            eventTypes.contains("dismiss") && eventTypes.contains("live_activity_dismiss")
        }
    }

    @Test
    fun notificationActionHandlerLaunchBuildersFcmAndPermissionHelpersCoverRuntimeBranches() {
        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        val rawPayload = """
            {
              "metadata": {
                "notification_id": "notif_action_1",
                "delivery_id": "delivery_action_1",
                "click_id": "click_action_1",
                "android_launch_url": "example.com/action"
              },
              "live_activity": {
                "activity_id": "live_action_1",
                "send_id": "send_action_1",
                "launch_url": "myapp://live-action"
              }
            }
        """.trimIndent()
        val actionEvents = mutableListOf<SendrealmNotificationActionEvent>()
        Sendrealm.addNotificationActionListener { actionEvents.add(it) }

        val actionIntent = Intent(Sendrealm.ACTION_NOTIFICATION_ACTION)
            .putExtra(Sendrealm.EXTRA_NOTIFICATION_ID, "notif_action_1")
            .putExtra(Sendrealm.EXTRA_DELIVERY_ID, "delivery_action_1")
            .putExtra(Sendrealm.EXTRA_CLICK_ID, "click_action_1")
            .putExtra(Sendrealm.EXTRA_ACTION_ID, "details")
            .putExtra(Sendrealm.EXTRA_LAUNCH_URL, "example.com/action")
            .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)

        Sendrealm.handleNotificationAction(context, actionIntent)

        waitUntil {
            val eventTypes = Sendrealm.testingQueuedEvents(context).map { it["eventType"] }
            eventTypes.contains("open") &&
                eventTypes.contains("click") &&
                eventTypes.contains("notification_action") &&
                eventTypes.contains("live_activity_action")
        }
        assertEquals("details", actionEvents.single().actionIdentifier)
        val launched = shadowOf(context as Application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://example.com/action", launched.dataString)
        assertEquals("notif_action_1", launched.getStringExtra(Sendrealm.EXTRA_NOTIFICATION_ID))
        assertFalse(launched.getBooleanExtra("com.sendrealm.sdk.extra.HANDLED", true))

        val duplicateEventCount = Sendrealm.testingQueuedEvents(context).size
        Sendrealm.handleNotificationAction(context, actionIntent)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(duplicateEventCount, Sendrealm.testingQueuedEvents(context).size)

        val externalIntent = Sendrealm.testingBuildExternalLaunchIntent(
            notificationId = "notif_builder",
            deliveryId = "delivery_builder",
            clickId = "click_builder",
            actionIdentifier = "open",
            launchUrl = "builder.example/path",
            rawPayload = rawPayload,
            tracked = false
        )
        assertEquals(Intent.ACTION_VIEW, externalIntent?.action)
        assertEquals("https://builder.example/path", externalIntent?.dataString)
        assertEquals("open", externalIntent?.getStringExtra(Sendrealm.EXTRA_ACTION_ID))
        assertFalse(externalIntent?.getBooleanExtra("com.sendrealm.sdk.extra.TRACKED", true) ?: true)

        val appLaunchIntent = Sendrealm.testingBuildAppLaunchIntent(
            context = context,
            notificationId = "notif_app",
            deliveryId = "delivery_app",
            clickId = "click_app",
            actionIdentifier = null,
            launchUrl = "plain destination",
            rawPayload = rawPayload,
            tracked = true
        )
        if (appLaunchIntent != null) {
            assertEquals("plain destination", appLaunchIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))
            assertTrue(appLaunchIntent.getBooleanExtra("com.sendrealm.sdk.extra.TRACKED", false))
        }

        Sendrealm.testingSetFcmTokenProvider({ "provided-fcm-token" }, deleteResult = false)
        assertEquals("provided-fcm-token", Sendrealm.testingAwaitFcmToken())
        assertEquals("provided-fcm-token", Sendrealm.testingAwaitFreshFcmToken())

        Sendrealm.testingMaybeRequestNotificationPermission(context)
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        Sendrealm.testingMaybeRequestNotificationPermission(activity)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun publicApisHandlePreInitInvalidAndMissingDevicePaths() {
        Sendrealm.login("pre_init_user", "PRE@EXAMPLE.COM")
        assertEquals("pre_init_user", Sendrealm.getState().externalUserId)
        assertEquals("pre@example.com", Sendrealm.getState().userEmail)
        Sendrealm.logout()
        assertNull(Sendrealm.getState().externalUserId)
        assertNull(Sendrealm.getState().userEmail)

        assertFalse(awaitBooleanCallback { callback -> Sendrealm.syncNotificationChannels(callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.registerLiveActivityToken("", callback = callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.deleteLiveActivityToken("", callback = callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.optIn(callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.optOut(callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.addTags(emptyMap(), callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.trackEvent("pre_init", callback = callback) })
        assertNull(awaitStringCallback { callback -> Sendrealm.refreshRegistrationToken(callback = callback) })
        assertNull(Sendrealm.handleNotificationOpen(null))
        assertNull(Sendrealm.handleNotificationOpen(Intent()))

        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        Sendrealm.requestPermission(activity)
        shadowOf(Looper.getMainLooper()).idle()

        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        assertFalse(
            awaitBooleanCallback { callback ->
                Sendrealm.registerLiveActivityToken("live-token", callback = callback)
            }
        )
        assertFalse(
            awaitBooleanCallback { callback ->
                Sendrealm.deleteLiveActivityToken("live-token", callback = callback)
            }
        )
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.addTags(mapOf("plan" to "pro"), callback) })
        assertFalse(awaitBooleanCallback { callback -> Sendrealm.optOut(callback) })

        Sendrealm.testingSetState(context, appId = "app_123", platform = "ios")
        assertTrue(awaitBooleanCallback { callback -> Sendrealm.syncNotificationChannels(callback) })
    }

    @Test
    fun initializeFailureStoresDiagnosticsWithoutRequestingFcmToken() {
        server.enqueue(status(500))
        val token = awaitStringCallback { callback ->
            Sendrealm.initialize(
                context = context,
                appId = "app_123",
                config = SendrealmConfig()
                    .setBaseUrl(server.url("/").toString())
                    .setExternalUserId("User_123")
                    .setUserEmail("USER@EXAMPLE.COM")
                    .setAutoRequestPermission(false)
                    .setForceRefreshRegistrationToken(false),
                callback = callback
            )
        }

        assertNull(token)
        val request = takeRequestBody()
        assertEquals("/v1/init", request.path)
        assertEquals("app_123", request.body["app_id"].asString)

        val diagnostics = Sendrealm.getDiagnostics(context)
        assertEquals(false, diagnostics.lastInitResult?.success)
        assertEquals("E_INIT_FAILED", diagnostics.lastSdkError?.code)
        assertEquals("options", diagnostics.apiUrlSource)
        assertEquals("User_123", Sendrealm.getState(context).externalUserId)
        assertEquals("user@example.com", Sendrealm.getState(context).userEmail)
    }

    @Test
    fun initializeOptInRefreshAndLoginUseProvidedFcmTokenAndSyncRegistration() {
        Sendrealm.testingSetFcmTokenProvider({ "fcm-token-1" })
        server.enqueue(status(200, initResponse("device_123")))
        server.enqueue(status(200, registerResponse("fcm-token-1")))
        server.enqueue(status(200, channelsResponse(emptyList())))

        val token = awaitStringCallback { callback ->
            Sendrealm.initialize(
                context = context,
                appId = "app_123",
                config = SendrealmConfig()
                    .setBaseUrl(server.url("/").toString())
                    .setAutoRequestPermission(false)
                    .setForceRefreshRegistrationToken(true),
                callback = callback
            )
        }

        assertEquals("fcm-token-1", token)
        var initRequest = takeRequestBody()
        var registerRequest = takeRequestBody()
        var syncRequest = takeRequestBody()
        assertEquals("/v1/init", initRequest.path)
        assertEquals("/v1/register", registerRequest.path)
        assertEquals("fcm-token-1", registerRequest.body["registration_id"].asString)
        assertEquals("/v1/notification-channels/sync", syncRequest.path)
        assertEquals("device_123", Sendrealm.getDeviceId())
        assertTrue(Sendrealm.getState(context).subscribed)

        Sendrealm.testingSetState(
            context = context,
            baseUrl = server.url("/").toString(),
            deviceId = "device_123",
            registrationToken = "fcm-token-1",
            subscribed = false,
            permissionGranted = true
        )
        Sendrealm.testingSetFcmTokenProvider({ "fcm-token-2" })
        server.enqueue(status(200))
        assertTrue(awaitBooleanCallback { callback -> Sendrealm.optIn(callback) })
        val optInRequest = takeRequestBody()
        assertEquals("/v1/subscription", optInRequest.path)
        assertEquals("fcm-token-2", optInRequest.body["registration_id"].asString)
        assertTrue(Sendrealm.getState(context).subscribed)

        server.enqueue(status(200, registerResponse("fcm-token-2")))
        server.enqueue(status(200, channelsResponse(emptyList())))
        assertEquals(
            "fcm-token-2",
            awaitStringCallback { callback ->
                Sendrealm.refreshRegistrationToken(forceRefresh = false, callback = callback)
            }
        )
        registerRequest = takeRequestBody()
        syncRequest = takeRequestBody()
        assertEquals("/v1/register", registerRequest.path)
        assertEquals("/v1/notification-channels/sync", syncRequest.path)

        server.enqueue(status(200, registerResponse("fcm-token-2")))
        server.enqueue(status(200, channelsResponse(emptyList())))
        Sendrealm.login("identity_user", "IDENTITY@EXAMPLE.COM")
        registerRequest = takeRequestBody()
        syncRequest = takeRequestBody()
        assertEquals("/v1/register", registerRequest.path)
        assertEquals("identity_user", registerRequest.body["user_external_id"].asString)
        assertEquals("identity@example.com", registerRequest.body["user_email"].asString)
        assertEquals("/v1/notification-channels/sync", syncRequest.path)
    }

    @Test
    fun lifecycleCallbacksAndMalformedStoredQueuesAreHandledSafely() {
        val prefs = context.getSharedPreferences("sendrealm_push", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("pending_events", "{bad")
            .putString("pending_tags", "{bad")
            .putString("pending_registrations", "{bad")
            .putString("pending_subscriptions", "{bad")
            .putString("synced_channel_ids", "{bad")
            .putString("recent_open_keys", "{bad")
            .putString("last_init_result", "{bad")
            .putString("last_register_result", "{bad")
            .putString("last_sdk_error", "{bad")
            .apply()

        assertTrue(Sendrealm.testingQueuedEvents(context).isEmpty())
        assertTrue(Sendrealm.testingQueuedTags(context).isEmpty())
        assertTrue(Sendrealm.testingQueuedRegistrations(context).isEmpty())
        assertTrue(Sendrealm.testingQueuedSubscriptions(context).isEmpty())
        assertTrue(Sendrealm.testingRecentOpenKeys(context).isEmpty())
        val diagnostics = Sendrealm.getDiagnostics(context)
        assertNull(diagnostics.lastInitResult)
        assertNull(diagnostics.lastRegisterResult)
        assertNull(diagnostics.lastSdkError)

        Sendrealm.testingSetState(context, appId = "app_123", platform = "ios")
        Sendrealm.testingSetFcmTokenProvider({ null })
        val utilityDiagnostics = Sendrealm.testingUtilityDiagnostics(context)
        assertEquals("https://push.example.com", utilityDiagnostics["overrideBaseUrl"])
        assertEquals("https://sdk-api.sendrealm.com", utilityDiagnostics["storedBaseUrl"])
        assertNull(utilityDiagnostics["defaultSound"])
        assertEquals("alert.wav", utilityDiagnostics["customSound"])
        assertEquals(NotificationManager.IMPORTANCE_NONE, utilityDiagnostics["importanceNone"])
        assertEquals(Notification.VISIBILITY_SECRET, utilityDiagnostics["visibilitySecret"])
        assertNull(utilityDiagnostics["invalidColor"])
        assertEquals("https://example.com/path", utilityDiagnostics["webLaunchUrl"])
        assertEquals("open app", utilityDiagnostics["plainLaunchUrl"])
        assertNull(utilityDiagnostics["blankPayload"])
        assertNull(utilityDiagnostics["badPayload"])
        assertEquals(42, utilityDiagnostics["normalizedNumber"])
        assertEquals(true, utilityDiagnostics["normalizedBoolean"])
        assertEquals("object-value", utilityDiagnostics["normalizedObject"])
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val callbacks = Sendrealm.testingCreateLifecycleCallbacks()
        callbacks.onActivityCreated(activity, Bundle())
        callbacks.onActivityStarted(activity)
        callbacks.onActivityResumed(activity)
        callbacks.onActivityPaused(activity)
        callbacks.onActivitySaveInstanceState(activity, Bundle())
        callbacks.onActivityStopped(activity)
        callbacks.onActivityDestroyed(activity)

        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Sendrealm.onNotificationReceived(
            context,
            SendrealmPayload(
                notification = SendrealmNotification("Foreground", "Shown"),
                metadata = SendrealmMetadata(notificationId = "foreground_1")
            ),
            """{"notification":{"title":"Foreground","body":"Shown"}}"""
        ).isForeground)
    }

    private fun status(code: Int, body: String? = null): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body ?: if (code in 200..299) "{}" else """{"error":{"message":"failed"}}""")

    private fun initResponse(deviceId: String): String =
        """
        {
          "status": 200,
          "data": {
            "app_id": "app_123",
            "device_id": "$deviceId",
            "platform": "android",
            "initialized_at": "2026-06-23T10:00:00.000Z",
            "fcm_app": {
              "application_id": "1:123:android:abc",
              "project_id": "sendrealm-demo",
              "sender_id": "123",
              "api_key": "firebase-api-key"
            }
          }
        }
        """.trimIndent()

    private fun registerResponse(registrationId: String): String =
        """
        {
          "status": 200,
          "data": {
            "app_id": "app_123",
            "device_id": "device_123",
            "registration_id": "$registrationId",
            "platform": "android",
            "registered_at": "2026-06-23T10:01:00.000Z"
          }
        }
        """.trimIndent()

    private fun channelsResponse(channels: List<String>): String {
        val channelJson = channels.joinToString(",") { channelId ->
            """{"channel_id":"$channelId","name":"$channelId","importance":"default"}"""
        }
        return """
        {
          "status": 200,
          "data": {
            "app_id": "app_123",
            "platform": "android",
            "synced_at": "2026-06-23T10:02:00.000Z",
            "channels": [$channelJson]
          }
        }
        """.trimIndent()
    }

    private fun awaitBooleanCallback(block: (((Boolean) -> Unit) -> Unit)): Boolean {
        var result: Boolean? = null
        block { success -> result = success }
        waitUntil { result != null }
        return result ?: false
    }

    private fun awaitStringCallback(block: (((String?) -> Unit) -> Unit)): String? {
        var result: String? = "pending"
        block { value -> result = value }
        waitUntil { result != "pending" }
        return result
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition())
    }

    private fun takeRequestBody(): RecordedJsonRequest {
        val request = server.takeRequest(2, TimeUnit.SECONDS)
            ?: throw AssertionError("Expected mock server request")
        return RecordedJsonRequest(
            method = request.method.orEmpty(),
            path = request.path?.substringBefore("?").orEmpty(),
            body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        )
    }

    private data class RecordedJsonRequest(
        val method: String,
        val path: String,
        val body: JsonObject
    )
}
