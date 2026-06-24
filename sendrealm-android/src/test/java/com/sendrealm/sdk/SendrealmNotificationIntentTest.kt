package com.sendrealm.sdk

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendrealmNotificationIntentTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Sendrealm.testingResetState(context)
    }

    @After
    fun tearDown() {
        Sendrealm.testingResetState(context)
    }

    @Test
    fun handleNotificationOpenNormalizesPayloadAndMarksIntentHandled() {
        Sendrealm.testingSetState(context, appId = null)
        val clicks = mutableListOf<SendrealmNotificationOpenResult>()
        val actions = mutableListOf<SendrealmNotificationActionEvent>()
        val rawPayload = """
            {
              "notification": {
                "title": "Order ready",
                "body": "Tap to view"
              },
              "metadata": {
                "android_launch_url": "myapp://orders/android",
                "launch_url": "https://example.com/orders/1"
              },
              "actions": [
                {
                  "id": "view",
                  "title": "View",
                  "launch_url": "myapp://orders/1"
                }
              ]
            }
        """.trimIndent()
        val intent = Intent()
            .putExtra(Sendrealm.EXTRA_CLICK_ID, "click_123")
            .putExtra(Sendrealm.EXTRA_ACTION_ID, "view")
            .putExtra(Sendrealm.EXTRA_PAYLOAD, rawPayload)

        Sendrealm.addNotificationClickListener { clicks.add(it) }
        Sendrealm.addNotificationActionListener { actions.add(it) }

        val result = Sendrealm.handleNotificationOpen(intent)
        val secondResult = Sendrealm.handleNotificationOpen(intent)

        assertEquals("click_123", result?.clickId)
        assertEquals("view", result?.actionIdentifier)
        assertEquals("Order ready", result?.payload?.notification?.title)
        assertEquals("view", result?.payload?.actions?.firstOrNull()?.id)
        assertTrue(intent.getBooleanExtra("com.sendrealm.sdk.extra.HANDLED", false))
        assertEquals(result?.clickId, secondResult?.clickId)
        assertEquals(1, clicks.size)
        assertEquals(1, actions.size)
        assertEquals("view", actions.single().actionIdentifier)
        assertTrue(Sendrealm.testingRecentOpenKeys(context).containsKey("click_123"))
    }

    @Test
    fun launchUrlResolutionPrefersExplicitThenAndroidThenGenericThenLiveActivity() {
        val notificationPayload = """
            {
              "metadata": {
                "android_launch_url": "myapp://android",
                "launch_url": "https://example.com/fallback"
              }
            }
        """.trimIndent()
        val liveActivityPayload = """
            {
              "live_activity": {
                "activity_id": "delivery_123",
                "launch_url": "myapp://live-activity"
              }
            }
        """.trimIndent()

        assertEquals(
            "myapp://explicit",
            Sendrealm.testingResolveLaunchUrl("myapp://explicit", notificationPayload)
        )
        assertEquals(
            "myapp://android",
            Sendrealm.testingResolveLaunchUrl(null, notificationPayload)
        )
        assertEquals(
            "https://example.com/fallback",
            Sendrealm.testingResolveLaunchUrl(
                null,
                """{"metadata":{"launch_url":"https://example.com/fallback"}}"""
            )
        )
        assertEquals(
            "myapp://live-activity",
            Sendrealm.testingResolveLaunchUrl(null, liveActivityPayload)
        )
        assertNull(Sendrealm.testingResolveLaunchUrl(null, "{malformed"))
    }

    @Test
    fun silentNotificationNotifiesListenerAndQueuesBackgroundEventWhenDeviceMissing() {
        Sendrealm.testingSetState(context, appId = null, deviceId = null)
        val silentEvents = mutableListOf<SendrealmSilentNotificationEvent>()
        val payload = SendrealmPayload(
            metadata = SendrealmMetadata(deliveryId = "delivery_123"),
            data = mapOf("sync" to true)
        )

        Sendrealm.addSilentNotificationListener { silentEvents.add(it) }
        val event = Sendrealm.onSilentNotificationReceived(
            context = context,
            payload = payload,
            rawPayload = """{"data":{"sync":true}}"""
        )

        assertFalse(event.isForeground)
        assertEquals(1, silentEvents.size)
        assertEquals("delivery_123", silentEvents.single().payload.metadata?.deliveryId)
        waitUntil { Sendrealm.testingQueueCounts(context).events == 1 }
        assertEquals(
            "background_notification_received",
            Sendrealm.testingQueuedEvents(context).single()["eventType"]
        )
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
}
