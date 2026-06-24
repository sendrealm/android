package com.sendrealm.sdk

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendrealmNotificationRenderingTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var service: SendrealmMessagingService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Sendrealm.testingResetState(context)
        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        notificationManager.cancelAll()
        service = Robolectric.buildService(SendrealmMessagingService::class.java).create().get()
    }

    @After
    fun tearDown() {
        service.testingSetBitmapLoader(null)
        notificationManager.cancelAll()
        Sendrealm.testingResetState(context)
    }

    @Test
    fun rendersNotificationUiWithImagesActionsLaunchUrlsAndTrackedEvents() {
        val loadedUrls = mutableListOf<String>()
        service.testingSetBitmapLoader { _, url ->
            loadedUrls.add(url)
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }

        service.testingParseSendrealmNotification(
            """
            {
              "notification": {
                "title": "Order ready",
                "body": "Tap to track",
                "android": {
                  "channelId": "orders",
                  "channelName": "Orders",
                  "channelDescription": "Order updates",
                  "importance": "low",
                  "priority": "high",
                  "color": "#123456",
                  "largeIcon": "https://cdn.example.com/large.png",
                  "bigPicture": "https://cdn.example.com/picture.png",
                  "vibration": false,
                  "ledColor": "#00FF00",
                  "lockscreenVisibility": "private"
                }
              },
              "metadata": {
                "notification_id": "notif_ui_1",
                "delivery_id": "delivery_1",
                "click_id": "click_1",
                "android_launch_url": "myapp://orders/android",
                "launch_url": "https://example.com/orders/1",
                "image_url": "https://cdn.example.com/fallback.png"
              },
              "actions": [
                {
                  "id": "view",
                  "title": "View order",
                  "launch_url": "myapp://orders/view"
                },
                {
                  "id": "track",
                  "text": "Track"
                },
                {
                  "id": "help",
                  "title": "Help",
                  "launch_url": "myapp://help"
                },
                {
                  "id": "ignored",
                  "title": "Ignored"
                }
              ]
            }
            """.trimIndent()
        )

        val notification = postedNotifications().single()
        val shadowNotification = shadowOf(notification)
        val channel = notificationManager.getNotificationChannel("orders")

        assertEquals("Order ready", shadowNotification.contentTitle)
        assertEquals("Tap to track", shadowNotification.contentText)
        assertEquals(Color.parseColor("#123456"), notification.color)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertEquals("Orders", channel.name.toString())
        assertEquals("Order updates", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertTrue(channel.shouldShowLights())
        assertEquals(Color.GREEN, channel.lightColor)
        assertEquals(
            listOf(
                "https://cdn.example.com/large.png",
                "https://cdn.example.com/picture.png"
            ),
            loadedUrls
        )
        assertNotNull(shadowNotification.bigPicture)

        assertEquals(3, notification.actions.size)
        assertEquals("View order", notification.actions[0].title.toString())
        assertEquals("Track", notification.actions[1].title.toString())
        assertEquals("Help", notification.actions[2].title.toString())

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Sendrealm.ACTION_NOTIFICATION_OPEN, contentIntent.action)
        assertEquals("notif_ui_1", contentIntent.getStringExtra(Sendrealm.EXTRA_NOTIFICATION_ID))
        assertEquals("delivery_1", contentIntent.getStringExtra(Sendrealm.EXTRA_DELIVERY_ID))
        assertEquals("click_1", contentIntent.getStringExtra(Sendrealm.EXTRA_CLICK_ID))
        assertEquals("myapp://orders/android", contentIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))
        assertTrue(contentIntent.getStringExtra(Sendrealm.EXTRA_PAYLOAD)?.contains("Order ready") == true)

        val viewActionIntent = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(Sendrealm.ACTION_NOTIFICATION_ACTION, viewActionIntent.action)
        assertEquals("view", viewActionIntent.getStringExtra(Sendrealm.EXTRA_ACTION_ID))
        assertEquals("myapp://orders/view", viewActionIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))

        val fallbackActionIntent = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals("track", fallbackActionIntent.getStringExtra(Sendrealm.EXTRA_ACTION_ID))
        assertEquals("myapp://orders/android", fallbackActionIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))

        val deleteIntent = shadowOf(notification.deleteIntent).savedIntent
        assertEquals(Sendrealm.ACTION_NOTIFICATION_DISMISS, deleteIntent.action)
        assertEquals("notif_ui_1", deleteIntent.getStringExtra(Sendrealm.EXTRA_NOTIFICATION_ID))

        waitUntil { Sendrealm.testingQueueCounts(context).events >= 2 }
        val queuedEvents = Sendrealm.testingQueuedEvents(context)
        assertTrue(
            queuedEvents.any {
                it["eventType"] == "delivery" && it["notificationId"] == "notif_ui_1"
            }
        )
        assertTrue(queuedEvents.any { it["eventType"] == "background_notification_received" })
    }

    @Test
    fun rendersAndEndsLiveActivityNotificationWithLaunchUrlsAndTrackedEvents() {
        service.testingParseSendrealmNotification(
            """
            {
              "live_activity": {
                "activity_id": "delivery_live_1",
                "send_id": "send_1",
                "event": "start",
                "title": "Driver nearby",
                "body": "Arriving soon",
                "progress": 0.42,
                "accent_color": "#336699",
                "launch_url": "myapp://live",
                "buttons": [
                  {
                    "id": "details",
                    "title": "Details",
                    "launch_url": "myapp://live/details"
                  },
                  {
                    "id": "mute",
                    "text": "Mute"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val notification = postedNotifications().single()
        val shadowNotification = shadowOf(notification)

        assertEquals("Driver nearby", shadowNotification.contentTitle)
        assertEquals("Arriving soon", shadowNotification.contentText)
        assertEquals(Color.parseColor("#336699"), notification.color)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(100, notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
        assertEquals(42, notification.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(2, notification.actions.size)

        val contentIntent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(Sendrealm.ACTION_NOTIFICATION_OPEN, contentIntent.action)
        assertEquals("myapp://live", contentIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))

        val detailsActionIntent = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertEquals(Sendrealm.ACTION_NOTIFICATION_ACTION, detailsActionIntent.action)
        assertEquals("details", detailsActionIntent.getStringExtra(Sendrealm.EXTRA_ACTION_ID))
        assertEquals("myapp://live/details", detailsActionIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))

        val muteActionIntent = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals("mute", muteActionIntent.getStringExtra(Sendrealm.EXTRA_ACTION_ID))
        assertEquals("myapp://live", muteActionIntent.getStringExtra(Sendrealm.EXTRA_LAUNCH_URL))

        waitUntil {
            Sendrealm.testingQueuedEvents(context)
                .any { it["eventType"] == "live_activity_start" }
        }
        val startEvent = Sendrealm.testingQueuedEvents(context)
            .single { it["eventType"] == "live_activity_start" }
        val startProperties = startEvent["properties"] as Map<*, *>
        assertEquals("delivery_live_1", startProperties["activity_id"])
        assertEquals("send_1", startProperties["send_id"])
        assertEquals("start", startProperties["source_event"])

        service.testingParseSendrealmNotification(
            """
            {
              "live_activity": {
                "activity_id": "delivery_live_1",
                "send_id": "send_1",
                "event": "end"
              }
            }
            """.trimIndent()
        )

        assertTrue(postedNotifications().isEmpty())
        waitUntil {
            Sendrealm.testingQueuedEvents(context)
                .any { it["eventType"] == "live_activity_end" }
        }
        val endEvent = Sendrealm.testingQueuedEvents(context)
            .single { it["eventType"] == "live_activity_end" }
        val endProperties = endEvent["properties"] as Map<*, *>
        assertEquals("delivery_live_1", endProperties["activity_id"])
        assertEquals("send_1", endProperties["send_id"])
        assertEquals("end", endProperties["source_event"])
    }

    @Test
    fun firebaseMessagingEntryPointsRouteSendrealmPayloadsAndTokenRefreshes() {
        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        service.testingSetBitmapLoader { _, _ ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }

        service.onMessageReceived(
            RemoteMessage.Builder("sender@sendrealm")
                .addData(
                    "sendrealm_v1",
                    """
                    {
                      "notification": {
                        "title": "From FCM",
                        "body": "Rendered through onMessageReceived"
                      },
                      "metadata": {
                        "notification_id": "notif_fcm_1",
                        "android_launch_url": "myapp://fcm"
                      }
                    }
                    """.trimIndent()
                )
                .build()
        )

        val notification = postedNotifications().single()
        assertEquals("From FCM", shadowOf(notification).contentTitle)
        waitUntil {
            Sendrealm.testingQueuedEvents(context)
                .any { it["notificationId"] == "notif_fcm_1" }
        }

        service.onMessageReceived(
            RemoteMessage.Builder("sender@sendrealm")
                .addData("sendrealm_v1", "{malformed")
                .build()
        )

        service.onNewToken("new-fcm-token")
        waitUntil(timeoutMs = 7_000) {
            Sendrealm.testingQueuedRegistrations(context)
                .any { it["registrationId"] == "new-fcm-token" }
        }
    }

    private fun postedNotifications(): List<Notification> =
        shadowOf(notificationManager).getAllNotifications()

    private fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition())
    }
}
