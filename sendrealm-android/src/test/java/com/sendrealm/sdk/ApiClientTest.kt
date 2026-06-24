package com.sendrealm.sdk

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlin.concurrent.thread

class ApiClientTest {
    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var apiClient: ApiClient
    private val responses = Collections.synchronizedList(mutableListOf<MockResponse>())
    private val requests = Collections.synchronizedList(mutableListOf<RecordedRequest>())

    @Volatile
    private var serverRunning = false

    @Before
    fun setUp() {
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverRunning = true
        serverThread = thread(start = true, name = "ApiClientTestServer") {
            while (serverRunning) {
                try {
                    handleConnection(serverSocket.accept())
                } catch (_: Exception) {
                    if (serverRunning) {
                        throw AssertionError("Mock server failed while running")
                    }
                }
            }
        }

        apiClient = ApiClient("http://127.0.0.1:${serverSocket.localPort}/")
    }

    @After
    fun tearDown() {
        serverRunning = false
        serverSocket.close()
        serverThread.join(1000)
    }

    @Test
    fun initializePostsDeviceContextAndParsesResponse() {
        enqueue(
            200,
            """
            {
              "status": 200,
              "data": {
                "app_id": "app_123",
                "device_id": "device_123",
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
        )

        val response = apiClient.initializeBlocking(
            appId = "app_123",
            platform = "android",
            deviceId = "device_existing",
            appVersion = "1.2.3",
            sdkVersion = "0.0.1",
            osVersion = "Android 15",
            deviceLocale = "en-US",
            timezone = "America/Sao_Paulo",
            androidPackageName = "com.example.app",
            deviceModel = "Pixel 9",
            apiUrlSource = "options",
            permissionStatus = "authorized",
            subscribed = true
        )

        assertNotNull(response)
        assertEquals(200, response?.status)
        assertEquals("device_123", response?.data?.deviceId)
        assertEquals("sendrealm-demo", response?.data?.fcmApp?.projectId)

        val request = singleRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/init", request.path)
        assertTrue(request.contentType.orEmpty().startsWith("application/json"))

        val body = request.jsonBody()
        assertEquals("app_123", body.string("app_id"))
        assertEquals("android", body.string("platform"))
        assertEquals("device_existing", body.string("device_id"))
        assertEquals("1.2.3", body.string("app_version"))
        assertEquals("0.0.1", body.string("sdk_version"))
        assertEquals("Android 15", body.string("os_version"))
        assertEquals("en-US", body.string("device_locale"))
        assertEquals("America/Sao_Paulo", body.string("timezone"))
        assertEquals("com.example.app", body.string("android_package_name"))
        assertEquals("Pixel 9", body.string("device_model"))
        assertEquals("options", body.string("api_url_source"))
        assertEquals("authorized", body.string("permission_status"))
        assertTrue(body["subscribed"].asBoolean)
    }

    @Test
    fun registerDevicePostsIdentityFieldsAndParsesResponse() {
        enqueue(
            200,
            """
            {
              "status": 200,
              "data": {
                "app_id": "app_123",
                "device_id": "device_123",
                "registration_id": "fcm-token",
                "platform": "android",
                "external_user_id": "user_123",
                "registered_at": "2026-06-23T10:01:00.000Z"
              }
            }
            """.trimIndent()
        )

        val response = apiClient.registerDeviceBlocking(
            appId = "app_123",
            registrationId = "fcm-token",
            platform = "android",
            deviceId = "device_123",
            userExternalId = "user_123",
            userEmail = "person@example.com",
            appVersion = "1.2.3",
            sdkVersion = "0.0.1",
            osVersion = "Android 15",
            deviceLocale = "pt-BR",
            timezone = "America/Sao_Paulo",
            androidPackageName = "com.example.app",
            deviceModel = "Pixel 9",
            apiUrlSource = "persisted",
            permissionStatus = "denied",
            subscribed = false,
            idempotencyKey = "register-key"
        )

        assertNotNull(response)
        assertEquals("fcm-token", response?.data?.registrationId)
        assertEquals("user_123", response?.data?.externalUserId)

        val request = singleRequest()
        assertEquals("/v1/register", request.path)

        val body = request.jsonBody()
        assertEquals("app_123", body.string("app_id"))
        assertEquals("fcm-token", body.string("registration_id"))
        assertEquals("device_123", body.string("device_id"))
        assertEquals("user_123", body.string("user_external_id"))
        assertEquals("person@example.com", body.string("user_email"))
        assertEquals("register-key", body.string("idempotency_key"))
        assertFalse(body["subscribed"].asBoolean)
    }

    @Test
    fun postsMutationRequestsToExpectedEndpoints() {
        repeat(6) { enqueue(200, "{}") }

        assertTrue(
            apiClient.trackEventBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                eventType = "notification_action",
                notificationId = "notification_123",
                properties = mapOf("action_id" to "view", "attempt" to 2),
                appVersion = "1.2.3",
                sdkVersion = "0.0.1",
                osVersion = "Android 15",
                deviceLocale = "en-US",
                timezone = "UTC",
                idempotencyKey = "event-key"
            )
        )
        assertTrue(
            apiClient.updateTagsBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                tags = mapOf("plan" to "pro", "trial" to false, "removed" to null)
            )
        )
        assertTrue(
            apiClient.updateSubscriptionBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                subscribed = false,
                registrationId = "fcm-token",
                sdkVersion = "0.0.1",
                permissionStatus = "denied",
                idempotencyKey = "subscription-key"
            )
        )
        assertTrue(
            apiClient.registerLiveActivityTokenBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                tokenType = "android_registration",
                token = "live-token",
                activityId = "activity_123",
                activityType = "DeliveryActivity",
                attributesType = "DeliveryAttributes"
            )
        )
        assertTrue(
            apiClient.deleteLiveActivityTokenBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                tokenType = "android_registration",
                token = "live-token",
                activityId = "activity_123",
                activityType = "DeliveryActivity",
                attributesType = "DeliveryAttributes"
            )
        )
        assertTrue(
            apiClient.updateTagsBlocking(
                appId = "app_123",
                deviceId = "device_123",
                platform = "android",
                tags = mapOf("role" to "admin")
            )
        )

        assertEquals(
            listOf(
                "POST /v1/track",
                "POST /v1/tags",
                "POST /v1/subscription",
                "POST /v1/live-activities/tokens",
                "DELETE /v1/live-activities/tokens",
                "POST /v1/tags"
            ),
            requests.map { "${it.method} ${it.path}" }
        )

        val eventBody = requests[0].jsonBody()
        assertEquals("notification_action", eventBody.string("event_type"))
        assertEquals("notification_123", eventBody.string("notification_id"))
        assertEquals("view", eventBody["properties"].asJsonObject.string("action_id"))
        assertEquals(2.0, eventBody["properties"].asJsonObject["attempt"].asDouble, 0.001)
        assertEquals("event-key", eventBody.string("idempotency_key"))

        val tagsBody = requests[1].jsonBody()
        assertEquals("pro", tagsBody["tags"].asJsonObject.string("plan"))
        assertFalse(tagsBody["tags"].asJsonObject["trial"].asBoolean)
        assertTrue(tagsBody["tags"].asJsonObject["removed"].isJsonNull)

        val subscriptionBody = requests[2].jsonBody()
        assertFalse(subscriptionBody["subscribed"].asBoolean)
        assertEquals("fcm-token", subscriptionBody.string("registration_id"))
        assertEquals("subscription-key", subscriptionBody.string("idempotency_key"))

        val registerLiveActivityBody = requests[3].jsonBody()
        assertEquals("android_registration", registerLiveActivityBody.string("token_type"))
        assertEquals("live-token", registerLiveActivityBody.string("token"))
        assertEquals("activity_123", registerLiveActivityBody.string("activity_id"))

        val deleteLiveActivityBody = requests[4].jsonBody()
        assertEquals("android_registration", deleteLiveActivityBody.string("token_type"))
        assertEquals("DeliveryActivity", deleteLiveActivityBody.string("activity_type"))
        assertEquals("DeliveryAttributes", deleteLiveActivityBody.string("attributes_type"))
    }

    @Test
    fun syncNotificationChannelsPostsAppContextAndParsesChannels() {
        enqueue(
            200,
            """
            {
              "status": 200,
              "data": {
                "app_id": "app_123",
                "platform": "android",
                "synced_at": "2026-06-23T10:02:00.000Z",
                "channels": [
                  {
                    "channel_id": "orders",
                    "name": "Orders",
                    "description": "Order updates",
                    "importance": "high",
                    "sound": "order_ping",
                    "vibration": true,
                    "led_color": "#22c55e",
                    "lockscreen_visibility": "public",
                    "is_default": true,
                    "updated_at": "2026-06-23T10:02:00.000Z"
                  }
                ]
              }
            }
            """.trimIndent()
        )

        val response = apiClient.syncNotificationChannelsBlocking(
            appId = "app_123",
            platform = "android"
        )

        assertNotNull(response)
        assertEquals("app_123", response?.data?.appId)
        assertEquals("orders", response?.data?.channels?.firstOrNull()?.channelId)
        assertEquals("Order updates", response?.data?.channels?.firstOrNull()?.description)
        assertEquals("#22c55e", response?.data?.channels?.firstOrNull()?.ledColor)
        assertTrue(response?.data?.channels?.firstOrNull()?.isDefault == true)

        val request = singleRequest()
        assertEquals("/v1/notification-channels/sync", request.path)
        assertEquals("app_123", request.jsonBody().string("app_id"))
    }

    @Test
    fun returnsFailureValuesForServerErrorsAndMalformedJson() {
        enqueue(500, """{"error":{"message":"boom"}}""")
        enqueue(200, """{"status":200,"data":""")
        enqueue(503, """{"error":{"message":"unavailable"}}""")

        val initResponse = apiClient.initializeBlocking(
            appId = "app_123",
            deviceId = null,
            appVersion = null,
            sdkVersion = "0.0.1",
            osVersion = null,
            deviceLocale = null,
            timezone = null,
            androidPackageName = null,
            deviceModel = null,
            apiUrlSource = null,
            permissionStatus = null,
            subscribed = null
        )
        val registerResponse = apiClient.registerDeviceBlocking(
            appId = "app_123",
            registrationId = "fcm-token",
            deviceId = "device_123",
            sdkVersion = "0.0.1"
        )
        val tracked = apiClient.trackEventBlocking(
            appId = "app_123",
            deviceId = "device_123",
            platform = "android",
            eventType = "open",
            sdkVersion = "0.0.1"
        )

        assertNull(initResponse)
        assertNull(registerResponse)
        assertFalse(tracked)
        assertEquals(
            listOf("/v1/init", "/v1/register", "/v1/track"),
            requests.map { it.path }
        )
    }

    private fun enqueue(status: Int, body: String) {
        responses.add(MockResponse(status, body))
    }

    private fun handleConnection(socket: Socket) {
        socket.use {
            socket.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    break
                }
                val separatorIndex = line.indexOf(':')
                if (separatorIndex > 0) {
                    headers[line.substring(0, separatorIndex)] = line.substring(separatorIndex + 1).trim()
                }
            }

            val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
            val bodyBuffer = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val next = reader.read(bodyBuffer, read, contentLength - read)
                if (next <= 0) {
                    break
                }
                read += next
            }

            val parts = requestLine.split(" ")
            requests.add(
                RecordedRequest(
                    method = parts[0],
                    path = parts[1].substringBefore("?"),
                    body = String(bodyBuffer, 0, read),
                    contentType = headers["Content-Type"]
                )
            )

            val response = synchronized(responses) {
                if (responses.isEmpty()) {
                    MockResponse(200, "{}")
                } else {
                    responses.removeAt(0)
                }
            }
            val responseBody = response.body.toByteArray(StandardCharsets.UTF_8)
            val statusText = if (response.status in 200..299) "OK" else "Error"
            val responseHeader = buildString {
                append("HTTP/1.1 ${response.status} $statusText\r\n")
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${responseBody.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)

            socket.getOutputStream().use { output ->
                output.write(responseHeader)
                output.write(responseBody)
                output.flush()
            }
        }
    }

    private fun singleRequest(): RecordedRequest {
        assertEquals(1, requests.size)
        return requests.first()
    }

    private data class MockResponse(val status: Int, val body: String)

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val body: String,
        val contentType: String?
    ) {
        fun jsonBody(): JsonObject = JsonParser.parseString(body).asJsonObject
    }

    private fun JsonObject.string(name: String): String = this[name].asString
}
