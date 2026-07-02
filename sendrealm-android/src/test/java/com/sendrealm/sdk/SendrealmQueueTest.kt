package com.sendrealm.sdk

import android.content.Context
import android.os.Looper
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SendrealmQueueTest {
    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        Sendrealm.testingResetState(context)
        Sendrealm.testingSetState(
            context = context,
            baseUrl = server.url("/").toString(),
            deviceId = "device_123",
            registrationToken = "fcm-token",
            subscribed = true,
            permissionGranted = true
        )
    }

    @After
    fun tearDown() {
        Sendrealm.testingResetState(context)
        server.shutdown()
    }

    @Test
    fun failedTagsAndEventsQueueThenFlushClearsAfterSuccess() {
        server.enqueue(status(500))
        val tagsResult = awaitBooleanCallback { callback ->
            Sendrealm.addTags(
                mapOf(
                    "plan" to "pro",
                    "signed_in" to true,
                    "removed" to null
                ),
                callback
            )
        }

        assertTrue(tagsResult)
        val initialTagsRequest = takeRequestBody()
        assertEquals("/v1/tags", initialTagsRequest.path)
        assertEquals("pro", initialTagsRequest.body["tags"].asJsonObject["plan"].asString)
        assertTrue(initialTagsRequest.body["tags"].asJsonObject["removed"].isJsonNull)

        server.enqueue(status(500))
        val eventResult = awaitBooleanCallback { callback ->
            Sendrealm.trackEvent(
                "checkout_completed",
                mapOf("order_id" to "order_123", "amount" to 42),
                callback
            )
        }

        assertTrue(eventResult)
        val initialEventRequest = takeRequestBody()
        assertEquals("/v1/track", initialEventRequest.path)
        assertEquals("checkout_completed", initialEventRequest.body["event_type"].asString)
        assertEquals("order_123", initialEventRequest.body["properties"].asJsonObject["order_id"].asString)

        val queuedEventKey = Sendrealm.testingQueuedEvents(context)
            .first()["idempotencyKey"] as String
        assertTrue(queuedEventKey.startsWith("checkout_completed-"))
        assertEquals(1, Sendrealm.testingQueueCounts(context).events)
        assertEquals(3, Sendrealm.testingQueueCounts(context).tags)
        assertTrue(Sendrealm.testingQueuedTags(context).containsKey("removed"))
        assertNull(Sendrealm.testingQueuedTags(context)["removed"])

        server.enqueue(status(500))
        server.enqueue(status(500))
        Sendrealm.testingFlushPendingWorkBlocking(context)

        val failedTagsFlush = takeRequestBody()
        val failedEventFlush = takeRequestBody()
        assertEquals("/v1/tags", failedTagsFlush.path)
        assertEquals("/v1/track", failedEventFlush.path)
        assertEquals(queuedEventKey, failedEventFlush.body["idempotency_key"].asString)
        assertEquals(1, Sendrealm.testingQueueCounts(context).events)
        assertEquals(3, Sendrealm.testingQueueCounts(context).tags)

        server.enqueue(status(200))
        server.enqueue(status(200))
        Sendrealm.testingFlushPendingWorkBlocking(context)

        assertEquals("/v1/tags", takeRequestBody().path)
        assertEquals("/v1/track", takeRequestBody().path)
        assertEquals(0, Sendrealm.testingQueueCounts(context).events)
        assertEquals(0, Sendrealm.testingQueueCounts(context).tags)
    }

    @Test
    fun subscriptionRetryPreservesIdempotencyKeyAndBacksOff() {
        Sendrealm.testingEnqueueSubscription(
            context = context,
            subscribed = false,
            registrationId = "fcm-token",
            reason = "opt_out_failed"
        )
        val original = Sendrealm.testingQueuedSubscriptions(context).single()
        val originalKey = original["idempotencyKey"] as String
        val originalNextAttemptAt = original["nextAttemptAt"] as Long

        server.enqueue(status(503))
        Sendrealm.testingFlushPendingWorkBlocking(context)

        val request = takeRequestBody()
        assertEquals("/v1/subscription", request.path)
        assertFalse(request.body["subscribed"].asBoolean)
        assertEquals("fcm-token", request.body["registration_id"].asString)
        assertEquals(originalKey, request.body["idempotency_key"].asString)

        val retried = Sendrealm.testingQueuedSubscriptions(context).single()
        assertEquals(originalKey, retried["idempotencyKey"])
        assertEquals(1, retried["retryCount"])
        assertTrue((retried["nextAttemptAt"] as Long) > originalNextAttemptAt)

        Sendrealm.testingFlushPendingWorkBlocking(context)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        assertEquals(1, Sendrealm.testingQueuedSubscriptions(context).size)
    }

    @Test
    fun registrationQueueDeduplicatesTokensAndLimitsStoredItems() {
        repeat(1001) { index ->
            Sendrealm.testingEnqueueRegistration(
                context = context,
                registrationId = "token_$index",
                reason = "test"
            )
        }
        Sendrealm.testingEnqueueRegistration(
            context = context,
            registrationId = "token_1000",
            reason = "latest"
        )

        val registrations = Sendrealm.testingQueuedRegistrations(context)

        assertEquals(1000, registrations.size)
        assertFalse(registrations.any { it["registrationId"] == "token_0" })
        assertEquals(1, registrations.count { it["registrationId"] == "token_1000" })
        assertEquals("latest", registrations.last()["reason"])
        assertNotNull(registrations.last()["idempotencyKey"])
    }

    @Test
    fun retryDelayIsExponentialAndCapped() {
        assertEquals(1_000L, Sendrealm.testingRetryDelayMs(0))
        assertEquals(2_000L, Sendrealm.testingRetryDelayMs(1))
        assertEquals(256_000L, Sendrealm.testingRetryDelayMs(20))
    }

    private fun status(code: Int): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(if (code in 200..299) "{}" else """{"error":{"message":"failed"}}""")

    private fun awaitBooleanCallback(block: (((Boolean) -> Unit) -> Unit)): Boolean {
        var result: Boolean? = null
        block { success -> result = success }
        waitUntil { result != null }
        return result ?: false
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
            path = request.path?.substringBefore("?").orEmpty(),
            body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        )
    }

    private data class RecordedJsonRequest(
        val path: String,
        val body: JsonObject
    )
}
