package com.sendrealm.sdk

import com.google.gson.Gson
import com.sendrealm.sdk.network.FcmApp
import com.sendrealm.sdk.network.InitApiResponse
import com.sendrealm.sdk.network.InitData
import com.sendrealm.sdk.network.NotificationChannelsSyncData
import com.sendrealm.sdk.network.NotificationChannelsSyncResponse
import com.sendrealm.sdk.network.RegisterDeviceData
import com.sendrealm.sdk.network.RegisterDeviceResponse
import com.sendrealm.sdk.network.RemoteNotificationChannel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendrealmNotificationModelsTest {
    private val gson = Gson()

    @Test
    fun parsesSilentPayloadWithoutNotification() {
        val payload = gson.fromJson(
            """
            {
              "metadata": {
                "notification_id": "notification-1",
                "delivery_id": "delivery-1"
              },
              "data": {
                "kind": "sync"
              }
            }
            """.trimIndent(),
            SendrealmPayload::class.java
        )

        assertNull(payload.notification)
        assertEquals("notification-1", payload.metadata?.notificationId)
        assertEquals("delivery-1", payload.metadata?.deliveryId)
        assertEquals("sync", payload.data?.get("kind"))
    }

    @Test
    fun parsesActionButtons() {
        val payload = gson.fromJson(
            """
            {
              "notification": {
                "title": "Order update",
                "body": "Open your order"
              },
              "actions": [
                {
                  "id": "view",
                  "text": "View",
                  "icon": "eye",
                  "launch_url": "myapp://orders/1"
                }
              ]
            }
            """.trimIndent(),
            SendrealmPayload::class.java
        )

        assertEquals("Order update", payload.notification?.title)
        assertEquals("view", payload.actions?.firstOrNull()?.id)
        assertEquals("View", payload.actions?.firstOrNull()?.text)
        assertEquals("View", payload.actions?.firstOrNull()?.displayTitle)
        assertEquals("eye", payload.actions?.firstOrNull()?.icon)
        assertEquals("myapp://orders/1", payload.actions?.firstOrNull()?.launchUrl)
    }

    @Test
    fun displayTitlePrefersTitleThenText() {
        assertEquals(
            "Open",
            SendrealmNotificationAction(id = "open", title = "Open", text = "View").displayTitle
        )
        assertEquals(
            "View",
            SendrealmNotificationAction(id = "view", text = "View").displayTitle
        )
        assertEquals("", SendrealmNotificationAction(id = "empty").displayTitle)
    }

    @Test
    fun parsesAndroidAndIosNotificationSettings() {
        val payload = gson.fromJson(
            """
            {
              "notification": {
                "title": "Flash sale",
                "body": "Ends soon",
                "android": {
                  "channelId": "promotions",
                  "channelName": "Promotions",
                  "channelDescription": "Marketing updates",
                  "smallIcon": "ic_stat_sale",
                  "largeIcon": "https://example.com/icon.png",
                  "bigPicture": "https://example.com/hero.png",
                  "color": "#ef4444",
                  "priority": "high",
                  "importance": "max",
                  "sound": "sale",
                  "vibration": false,
                  "ledColor": "#22c55e",
                  "lockscreenVisibility": "private"
                },
                "ios": {
                  "badge": 3,
                  "sound": "default"
                }
              }
            }
            """.trimIndent(),
            SendrealmPayload::class.java
        )

        val android = payload.notification?.android
        val ios = payload.notification?.ios

        assertEquals("promotions", android?.channelId)
        assertEquals("Promotions", android?.channelName)
        assertEquals("Marketing updates", android?.channelDescription)
        assertEquals("ic_stat_sale", android?.smallIcon)
        assertEquals("https://example.com/icon.png", android?.largeIcon)
        assertEquals("https://example.com/hero.png", android?.bigPicture)
        assertEquals("#ef4444", android?.color)
        assertEquals("high", android?.priority)
        assertEquals("max", android?.importance)
        assertEquals("sale", android?.sound)
        assertFalse(android?.vibration ?: true)
        assertEquals("#22c55e", android?.ledColor)
        assertEquals("private", android?.lockscreenVisibility)
        assertEquals(3, ios?.badge)
        assertEquals("default", ios?.sound)
    }

    @Test
    fun parsesCamelCaseAliases() {
        val payload = gson.fromJson(
            """
            {
              "metadata": {
                "notificationId": "notification-2",
                "deliveryId": "delivery-2",
                "androidLaunchUrl": "myapp://home",
                "imageUrl": "https://example.com/hero.png"
              },
              "actions": [
                {
                  "id": "view",
                  "title": "View",
                  "launchUrl": "myapp://orders/2"
                }
              ],
              "liveActivity": {
                "activityId": "order-456",
                "sendId": "send-456",
                "activityType": "DeliveryActivity",
                "attributesType": "DeliveryAttributes",
                "imageUrl": "https://example.com/order-456.png",
                "accentColor": "#0ea5e9",
                "launchUrl": "myapp://orders/456",
                "contentState": {
                  "driver": "Leo"
                },
                "staleDate": "2026-06-22T19:00:00.000Z",
                "dismissalDate": "2026-06-22T19:10:00.000Z",
                "relevanceScore": 0.8,
                "buttons": [
                  {
                    "id": "track",
                    "title": "Track",
                    "launchUrl": "myapp://orders/456/track"
                  }
                ]
              }
            }
            """.trimIndent(),
            SendrealmPayload::class.java
        )

        assertEquals("notification-2", payload.metadata?.notificationId)
        assertEquals("delivery-2", payload.metadata?.deliveryId)
        assertEquals("myapp://home", payload.metadata?.androidLaunchUrl)
        assertEquals("https://example.com/hero.png", payload.metadata?.imageUrl)
        assertEquals("myapp://orders/2", payload.actions?.firstOrNull()?.launchUrl)
        assertEquals("order-456", payload.liveActivity?.activityId)
        assertEquals("send-456", payload.liveActivity?.sendId)
        assertEquals("DeliveryActivity", payload.liveActivity?.activityType)
        assertEquals("DeliveryAttributes", payload.liveActivity?.attributesType)
        assertEquals("https://example.com/order-456.png", payload.liveActivity?.imageUrl)
        assertEquals("#0ea5e9", payload.liveActivity?.accentColor)
        assertEquals("myapp://orders/456", payload.liveActivity?.launchUrl)
        assertEquals("Leo", payload.liveActivity?.contentState?.get("driver"))
        assertEquals("2026-06-22T19:00:00.000Z", payload.liveActivity?.staleDate)
        assertEquals("2026-06-22T19:10:00.000Z", payload.liveActivity?.dismissalDate)
        assertEquals(0.8, payload.liveActivity?.relevanceScore ?: 0.0, 0.001)
        assertEquals("myapp://orders/456/track", payload.liveActivity?.buttons?.firstOrNull()?.launchUrl)
    }

    @Test
    fun parsesLiveActivityPayload() {
        val payload = gson.fromJson(
            """
            {
              "live_activity": {
                "activity_id": "order-123",
                "send_id": "send-123",
                "activity_type": "DeliveryActivity",
                "attributes_type": "DeliveryAttributes",
                "event": "update",
                "title": "Delivery",
                "status": "Driver nearby",
                "progress": 0.75,
                "image_url": "https://example.com/order.png",
                "accent_color": "#22c55e",
                "launch_url": "myapp://orders/123",
                "content_state": {
                  "driver": "Ana",
                  "stops_remaining": 2
                },
                "attributes": {
                  "order_id": "order-123"
                },
                "priority": 10,
                "stale_date": "2026-06-22T18:00:00.000Z",
                "dismissal_date": "2026-06-22T18:10:00.000Z",
                "relevance_score": 0.9,
                "sound": "default",
                "buttons": [
                  {
                    "id": "track",
                    "text": "Track",
                    "launch_url": "myapp://orders/123/track"
                  }
                ]
              }
            }
            """.trimIndent(),
            SendrealmPayload::class.java
        )

        assertEquals("order-123", payload.liveActivity?.activityId)
        assertEquals("send-123", payload.liveActivity?.sendId)
        assertEquals("DeliveryActivity", payload.liveActivity?.activityType)
        assertEquals("DeliveryAttributes", payload.liveActivity?.attributesType)
        assertEquals("update", payload.liveActivity?.event)
        assertEquals("Driver nearby", payload.liveActivity?.status)
        assertEquals(0.75, payload.liveActivity?.progress ?: 0.0, 0.001)
        assertEquals("https://example.com/order.png", payload.liveActivity?.imageUrl)
        assertEquals("#22c55e", payload.liveActivity?.accentColor)
        assertEquals("Ana", payload.liveActivity?.contentState?.get("driver"))
        assertEquals(2.0, payload.liveActivity?.contentState?.get("stops_remaining"))
        assertEquals("order-123", payload.liveActivity?.attributes?.get("order_id"))
        assertEquals(10.0, payload.liveActivity?.priority ?: 0.0, 0.001)
        assertEquals("2026-06-22T18:00:00.000Z", payload.liveActivity?.staleDate)
        assertEquals(
            "2026-06-22T18:10:00.000Z",
            payload.liveActivity?.dismissalDate
        )
        assertEquals(0.9, payload.liveActivity?.relevanceScore ?: 0.0, 0.001)
        assertEquals("default", payload.liveActivity?.sound)
        assertEquals("track", payload.liveActivity?.buttons?.firstOrNull()?.id)
        assertEquals("Track", payload.liveActivity?.buttons?.firstOrNull()?.displayTitle)
        assertTrue(payload.liveActivity?.buttons?.isNotEmpty() == true)
    }

    @Test
    fun foregroundEventsAndDefaultModelConstructorsExposeExpectedDefaults() {
        val payload = SendrealmPayload(
            metadata = SendrealmMetadata(
                notificationId = "notification-defaults",
                androidLaunchUrl = "myapp://defaults"
            )
        )
        val foregroundEvent = SendrealmForegroundNotificationEvent(
            payload = payload,
            rawPayload = """{"metadata":{"notification_id":"notification-defaults"}}""",
            isForeground = true
        )

        assertEquals(payload, foregroundEvent.payload)
        assertEquals("""{"metadata":{"notification_id":"notification-defaults"}}""", foregroundEvent.rawPayload)
        assertTrue(foregroundEvent.isForeground)
        assertEquals("notification-defaults", foregroundEvent.notificationId)
        assertEquals("myapp://defaults", foregroundEvent.launchUrl)
        assertTrue(foregroundEvent.shouldDisplay)
        foregroundEvent.preventDefault()
        assertFalse(foregroundEvent.shouldDisplay)
        assertTrue(SendrealmForegroundPresentationOptions().display)

        val minimalLiveActivity = SendrealmLiveActivity(activityId = "activity-defaults")
        assertEquals("activity-defaults", minimalLiveActivity.activityId)
        assertNull(minimalLiveActivity.subtitle)
        assertNull(minimalLiveActivity.eta)

        val minimalChannel = RemoteNotificationChannel(
            channelId = "general",
            name = "General"
        )
        assertFalse(minimalChannel.isDefault)
        assertNull(minimalChannel.updatedAt)
    }

    @Test
    fun kotlinDataClassGeneratedMethodsRemainStable() {
        val fcmApp = FcmApp(
            applicationId = "1:123:android:abc",
            projectId = "sendrealm-demo",
            senderId = "123",
            apiKey = "firebase-api-key"
        )
        val initData = InitData(
            appId = "app_123",
            deviceId = "device_123",
            platform = "android",
            initializedAt = "2026-06-23T10:00:00.000Z",
            fcmApp = fcmApp
        )
        val registerData = RegisterDeviceData(
            appId = "app_123",
            deviceId = "device_123",
            registrationId = "fcm-token",
            platform = "android",
            externalUserId = "user_123",
            registeredAt = "2026-06-23T10:01:00.000Z"
        )
        val remoteChannel = RemoteNotificationChannel(
            channelId = "orders",
            name = "Orders",
            description = "Order updates",
            importance = "high",
            sound = "default",
            vibration = true,
            ledColor = "#ff0000",
            lockscreenVisibility = "private",
            isDefault = true,
            updatedAt = "2026-06-23T10:02:00.000Z"
        )
        val liveActivity = SendrealmLiveActivity(
            activityId = "activity_123",
            sendId = "send_123",
            activityType = "DeliveryActivity",
            attributesType = "DeliveryAttributes",
            event = "start",
            title = "Delivery",
            subtitle = "On the way",
            body = "Driver nearby",
            status = "active",
            progress = 0.5,
            eta = "10 min",
            imageUrl = "https://example.com/image.png",
            accentColor = "#00ff00",
            launchUrl = "myapp://delivery",
            contentState = mapOf("driver" to "Ana"),
            attributes = mapOf("order_id" to "order_123"),
            priority = 10.0,
            staleDate = "2026-06-23T10:10:00.000Z",
            dismissalDate = "2026-06-23T10:20:00.000Z",
            relevanceScore = 0.8,
            sound = "default",
            buttons = listOf(SendrealmNotificationAction("track", title = "Track"))
        )

        assertEquals(fcmApp, fcmApp.copy())
        assertEquals("1:123:android:abc", fcmApp.component1())
        assertEquals("sendrealm-demo", fcmApp.component2())
        assertEquals("123", fcmApp.component3())
        assertEquals("firebase-api-key", fcmApp.component4())
        assertEquals(initData, InitApiResponse(200, initData).component2())
        assertEquals("app_123", initData.component1())
        assertEquals("device_123", initData.component2())
        assertEquals("android", initData.component3())
        assertEquals("2026-06-23T10:00:00.000Z", initData.component4())
        assertEquals(fcmApp, initData.component5())
        assertEquals(registerData, RegisterDeviceResponse(200, registerData).component2())
        assertEquals("app_123", registerData.component1())
        assertEquals("device_123", registerData.component2())
        assertEquals("fcm-token", registerData.component3())
        assertEquals("android", registerData.component4())
        assertEquals("user_123", registerData.component5())
        assertEquals("2026-06-23T10:01:00.000Z", registerData.component6())
        assertEquals(
            remoteChannel,
            NotificationChannelsSyncResponse(
                status = 200,
                data = NotificationChannelsSyncData(
                    appId = "app_123",
                    platform = "android",
                    syncedAt = "2026-06-23T10:03:00.000Z",
                    channels = listOf(remoteChannel)
                )
            ).data.channels.single()
        )
        assertEquals("orders", remoteChannel.component1())
        assertEquals("Orders", remoteChannel.component2())
        assertEquals("Order updates", remoteChannel.component3())
        assertEquals("high", remoteChannel.component4())
        assertEquals("default", remoteChannel.component5())
        assertEquals(true, remoteChannel.component6())
        assertEquals("#ff0000", remoteChannel.component7())
        assertEquals("private", remoteChannel.component8())
        assertTrue(remoteChannel.component9())
        assertEquals("2026-06-23T10:02:00.000Z", remoteChannel.component10())
        assertEquals("Delivery", liveActivity.copy().title)
        assertTrue(liveActivity.toString().contains("activity_123"))
        assertEquals(liveActivity, liveActivity.copy())
        assertEquals("activity_123", liveActivity.component1())
        assertEquals("send_123", liveActivity.component2())
        assertEquals("DeliveryActivity", liveActivity.component3())
        assertEquals("DeliveryAttributes", liveActivity.component4())
        assertEquals("start", liveActivity.component5())
        assertEquals("Delivery", liveActivity.component6())
        assertEquals("On the way", liveActivity.component7())
        assertEquals("Driver nearby", liveActivity.component8())
        assertEquals("active", liveActivity.component9())
        assertEquals(0.5, liveActivity.component10() ?: 0.0, 0.001)
        assertEquals("10 min", liveActivity.component11())
        assertEquals("https://example.com/image.png", liveActivity.component12())
        assertEquals("#00ff00", liveActivity.component13())
        assertEquals("myapp://delivery", liveActivity.component14())
        assertEquals("Ana", liveActivity.component15()?.get("driver"))
        assertEquals("order_123", liveActivity.component16()?.get("order_id"))
        assertEquals(10.0, liveActivity.component17() ?: 0.0, 0.001)
        assertEquals("2026-06-23T10:10:00.000Z", liveActivity.component18())
        assertEquals("2026-06-23T10:20:00.000Z", liveActivity.component19())
        assertEquals(0.8, liveActivity.component20() ?: 0.0, 0.001)
        assertEquals("default", liveActivity.component21())
        assertEquals("track", liveActivity.component22()?.single()?.id)
    }
}
