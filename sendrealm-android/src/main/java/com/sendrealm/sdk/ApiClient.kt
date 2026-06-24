package com.sendrealm.sdk

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sendrealm.sdk.network.InitApiResponse
import com.sendrealm.sdk.network.NotificationChannelsSyncResponse
import com.sendrealm.sdk.network.RegisterDeviceResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(
    baseUrl: String
) {
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val gsonWithNulls = GsonBuilder().serializeNulls().create()

    fun initializeBlocking(
        appId: String,
        platform: String = "android",
        deviceId: String?,
        appVersion: String?,
        sdkVersion: String,
        osVersion: String?,
        deviceLocale: String?,
        timezone: String?,
        androidPackageName: String?,
        deviceModel: String?,
        apiUrlSource: String?,
        permissionStatus: String?,
        subscribed: Boolean?
    ): InitApiResponse? {
        val endpoint = "$normalizedBaseUrl/v1/init"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "platform" to platform,
                "device_id" to deviceId,
                "app_version" to appVersion,
                "sdk_version" to sdkVersion,
                "os_version" to osVersion,
                "device_locale" to deviceLocale,
                "timezone" to timezone,
                "android_package_name" to androidPackageName,
                "device_model" to deviceModel,
                "api_url_source" to apiUrlSource,
                "permission_status" to permissionStatus,
                "subscribed" to subscribed
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return execute(request, InitApiResponse::class.java)
    }

    fun registerDeviceBlocking(
        appId: String,
        registrationId: String,
        platform: String = "android",
        deviceId: String,
        apnsDeviceToken: String? = null,
        userExternalId: String? = null,
        userEmail: String? = null,
        appVersion: String? = null,
        sdkVersion: String,
        osVersion: String? = null,
        deviceLocale: String? = null,
        timezone: String? = null,
        androidPackageName: String? = null,
        deviceModel: String? = null,
        apiUrlSource: String? = null,
        permissionStatus: String? = null,
        subscribed: Boolean? = null,
        idempotencyKey: String? = null
    ): RegisterDeviceResponse? {
        val endpoint = "$normalizedBaseUrl/v1/register"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "registration_id" to registrationId,
                "platform" to platform,
                "device_id" to deviceId,
                "apns_device_token" to apnsDeviceToken,
                "user_external_id" to userExternalId,
                "user_email" to userEmail,
                "app_version" to appVersion,
                "sdk_version" to sdkVersion,
                "os_version" to osVersion,
                "device_locale" to deviceLocale,
                "timezone" to timezone,
                "android_package_name" to androidPackageName,
                "device_model" to deviceModel,
                "api_url_source" to apiUrlSource,
                "permission_status" to permissionStatus,
                "subscribed" to subscribed,
                "idempotency_key" to idempotencyKey
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return execute(request, RegisterDeviceResponse::class.java)
    }

    fun trackEventBlocking(
        appId: String,
        deviceId: String,
        platform: String,
        eventType: String,
        notificationId: String? = null,
        properties: Map<String, Any?>? = null,
        appVersion: String? = null,
        sdkVersion: String,
        osVersion: String? = null,
        deviceLocale: String? = null,
        timezone: String? = null,
        idempotencyKey: String? = null
    ): Boolean {
        val endpoint = "$normalizedBaseUrl/v1/track"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "device_id" to deviceId,
                "platform" to platform,
                "event_type" to eventType,
                "notification_id" to notificationId,
                "properties" to properties,
                "app_version" to appVersion,
                "sdk_version" to sdkVersion,
                "os_version" to osVersion,
                "device_locale" to deviceLocale,
                "timezone" to timezone,
                "idempotency_key" to idempotencyKey
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return executeWithoutResponse(request)
    }

    fun updateTagsBlocking(
        appId: String,
        deviceId: String,
        platform: String,
        tags: Map<String, Any?>
    ): Boolean {
        val endpoint = "$normalizedBaseUrl/v1/tags"
        val bodyJson = gsonWithNulls.toJson(
            mapOf(
                "app_id" to appId,
                "device_id" to deviceId,
                "platform" to platform,
                "tags" to tags
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return executeWithoutResponse(request)
    }

    fun updateSubscriptionBlocking(
        appId: String,
        deviceId: String,
        platform: String,
        subscribed: Boolean,
        registrationId: String? = null,
        apnsDeviceToken: String? = null,
        appVersion: String? = null,
        sdkVersion: String,
        osVersion: String? = null,
        deviceLocale: String? = null,
        timezone: String? = null,
        permissionStatus: String? = null,
        idempotencyKey: String? = null
    ): Boolean {
        val endpoint = "$normalizedBaseUrl/v1/subscription"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "device_id" to deviceId,
                "platform" to platform,
                "subscribed" to subscribed,
                "registration_id" to registrationId,
                "apns_device_token" to apnsDeviceToken,
                "app_version" to appVersion,
                "sdk_version" to sdkVersion,
                "os_version" to osVersion,
                "device_locale" to deviceLocale,
                "timezone" to timezone,
                "permission_status" to permissionStatus,
                "idempotency_key" to idempotencyKey
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return executeWithoutResponse(request)
    }

    fun syncNotificationChannelsBlocking(
        appId: String,
        platform: String = "android"
    ): NotificationChannelsSyncResponse? {
        val endpoint = "$normalizedBaseUrl/v1/notification-channels/sync"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "platform" to platform
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return execute(request, NotificationChannelsSyncResponse::class.java)
    }

    fun registerLiveActivityTokenBlocking(
        appId: String,
        deviceId: String,
        platform: String,
        tokenType: String,
        token: String,
        activityId: String? = null,
        activityType: String? = null,
        attributesType: String? = null
    ): Boolean {
        val endpoint = "$normalizedBaseUrl/v1/live-activities/tokens"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "device_id" to deviceId,
                "platform" to platform,
                "token_type" to tokenType,
                "token" to token,
                "activity_id" to activityId,
                "activity_type" to activityType,
                "attributes_type" to attributesType
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return executeWithoutResponse(request)
    }

    fun deleteLiveActivityTokenBlocking(
        appId: String,
        deviceId: String,
        platform: String,
        tokenType: String,
        token: String,
        activityId: String? = null,
        activityType: String? = null,
        attributesType: String? = null
    ): Boolean {
        val endpoint = "$normalizedBaseUrl/v1/live-activities/tokens"
        val bodyJson = gson.toJson(
            mapOf(
                "app_id" to appId,
                "device_id" to deviceId,
                "platform" to platform,
                "token_type" to tokenType,
                "token" to token,
                "activity_id" to activityId,
                "activity_type" to activityType,
                "attributes_type" to attributesType
            )
        )
        val body = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint)
            .delete(body)
            .build()

        Log.d("ApiClient", "Making request to: $endpoint")
        Log.d("ApiClient", "Request body: $bodyJson")

        return executeWithoutResponse(request)
    }

    private fun <T> execute(request: Request, clazz: Class<T>): T? {
        return try {
            client.newCall(request).execute().use { response ->
                Log.d("ApiClient", "Response code: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e("ApiClient", "Request failed with code: ${response.code}")
                    Log.e("ApiClient", "Response message: ${response.message}")
                    val errorBody = response.body?.string()
                    Log.e("ApiClient", "Error body: $errorBody")
                    return null
                }

                val raw = response.body?.string()
                Log.d("ApiClient", "Response body: $raw")

                if (raw == null) {
                    Log.e("ApiClient", "Response body is null")
                    return null
                }

                gson.fromJson(raw, clazz)
            }
        } catch (e: IOException) {
            Log.e("ApiClient", "Network error", e)
            null
        } catch (e: Exception) {
            Log.e("ApiClient", "Unexpected error", e)
            null
        }
    }

    private fun executeWithoutResponse(request: Request): Boolean {
        return try {
            client.newCall(request).execute().use { response ->
                Log.d("ApiClient", "Response code: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e("ApiClient", "Request failed with code: ${response.code}")
                    Log.e("ApiClient", "Response message: ${response.message}")
                    val errorBody = response.body?.string()
                    Log.e("ApiClient", "Error body: $errorBody")
                    return false
                }

                true
            }
        } catch (e: IOException) {
            Log.e("ApiClient", "Network error", e)
            false
        } catch (e: Exception) {
            Log.e("ApiClient", "Unexpected error", e)
            false
        }
    }
}
