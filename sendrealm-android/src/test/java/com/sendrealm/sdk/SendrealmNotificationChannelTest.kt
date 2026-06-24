package com.sendrealm.sdk

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendrealmNotificationChannelTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        Sendrealm.testingResetState(context)
    }

    @After
    fun tearDown() {
        Sendrealm.testingResetState(context)
    }

    @Test
    fun createNotificationChannelAppliesRenderingSettings() {
        val created = Sendrealm.createNotificationChannel(
            context,
            SendrealmNotificationChannelConfig(
                id = "orders",
                name = "Orders",
                description = "Order updates",
                importance = NotificationManager.IMPORTANCE_LOW,
                enableVibration = false,
                enableLights = true,
                lightColor = Color.GREEN,
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE,
                showBadge = false
            )
        )

        val channel = notificationManager.getNotificationChannel("orders")
        val state = Sendrealm.getNotificationChannels(context).single { it.id == "orders" }

        assertTrue(created)
        assertEquals("Orders", channel.name.toString())
        assertEquals("Order updates", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertFalse(channel.shouldVibrate())
        assertTrue(channel.shouldShowLights())
        assertEquals(Color.GREEN, channel.lightColor)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canShowBadge())
        assertEquals("orders", state.id)
        assertEquals("Orders", state.name)
        assertEquals("Order updates", state.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, state.importance)
        assertFalse(state.enableVibration)
        assertTrue(state.enableLights)
        assertEquals(Color.GREEN, state.lightColor)
    }

    @Test
    fun invalidOrDeletedChannelsAreHandledSafely() {
        assertFalse(
            Sendrealm.createNotificationChannel(
                context,
                SendrealmNotificationChannelConfig(id = "", name = "Missing id")
            )
        )
        assertFalse(Sendrealm.deleteNotificationChannel(context, ""))

        assertTrue(
            Sendrealm.createNotificationChannel(
                context,
                SendrealmNotificationChannelConfig(id = "updates", name = "Updates")
            )
        )
        assertTrue(Sendrealm.deleteNotificationChannel(context, "updates"))
        assertNull(notificationManager.getNotificationChannel("updates"))
    }
}
