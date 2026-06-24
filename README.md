# Sendrealm Android SDK

Native Android SDK for Kotlin and Java apps. The SDK registers the device with Sendrealm, syncs the Firebase Cloud Messaging registration token, displays Sendrealm notifications, opens deep links or external links, and tracks lifecycle events.

## Platform Support

- Minimum SDK: Android API 27.
- Current delivery provider: Firebase Cloud Messaging.
- Required device capability: Google Play services / Google services.
- Huawei Mobile Services support is planned, but not available yet.
- Kotlin and Java Android apps can use this SDK. The examples below use Kotlin.

## Why Initialization Is Required

`Sendrealm.initialize(...)` must run before other SDK calls. Initialization does the work that makes every later method possible:

- Stores the `appId`, API base URL, platform, and optional identity.
- Calls the Sendrealm SDK API `/v1/init` endpoint.
- Creates or restores a Sendrealm `device_id`.
- Receives Firebase app configuration from the server.
- Initializes Firebase when the host app has not already done so.
- Gets the FCM registration token.
- Registers that token with `/v1/register`.
- Starts lifecycle tracking for foreground/background behavior.

If initialization has not completed, methods like `addTags`, `trackEvent`, `optIn`, and notification open tracking may fail because the SDK does not yet know the Sendrealm device ID or token.

## Install

When the SDK is published, use the package version from your dependency registry:

```kotlin
dependencies {
    implementation("com.sendrealm:sendrealm-android:<version>")
}
```

For local development from this repository, include the Gradle module from `sendrealm-android` in your app and add a project dependency:

```kotlin
dependencies {
    implementation(project(":sendrealm-android"))
}
```

The library manifest includes:

- `android.permission.INTERNET`
- `android.permission.POST_NOTIFICATIONS`
- `SendrealmMessagingService`
- `SendrealmNotificationActionReceiver`

## Google Services Requirement

Android push delivery currently depends on Firebase Cloud Messaging, which requires Google services. The SDK API returns the Firebase app configuration during initialization, and the SDK uses that configuration to get an FCM token.

At this time:

- Google Play services devices are supported.
- The host app's Android package name must match the Firebase Android app configured in Sendrealm.
- Android emulators should use a Google APIs or Google Play image.
- Devices without Google services, including many Huawei-only devices, are not supported yet.
- Huawei/HMS push support is planned for a future provider path.

## Quick Start

Initialize once from your launcher `Activity` or application startup flow:

```kotlin
import com.sendrealm.sdk.SendrealmConfig
import com.sendrealm.sdk.Sendrealm

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val config = SendrealmConfig()
            .setBaseUrl("https://sdk-api.sendrealm.com")
            .setEnvironment("development")
            .setAutoRequestPermission(false)

        Sendrealm.initialize(
            context = this,
            appId = "YOUR_SENDREALM_APP_ID",
            config = config
        ) { token ->
            if (token != null) {
                Log.d("Push", "Registered FCM token: $token")
            } else {
                Log.w("Push", "Sendrealm initialized, but token registration failed")
            }
        }
    }
}
```

Omit `setEnvironment` for production, or set it to `development` for dev/test
builds. Development devices only receive development push notifications and
broadcasts.

Ask for notification permission when the user is ready:

```kotlin
Sendrealm.requestPermission(this)
```

Handle notification opens from your launcher activity:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    Sendrealm.handleNotificationOpen(intent)
}
```

## Permissions

Android notification permission behavior depends on OS version:

| Android Version | Behavior |
| --- | --- |
| Android 13 / API 33 and newer | Runtime permission prompt is required for `POST_NOTIFICATIONS`. Use `requestPermission(activity)`. |
| Android 12 / API 32 and below | No runtime notification permission prompt exists. Notifications are allowed by default unless disabled in system settings. |

Use `hasNotificationPermission(context)` to check SDK permission state. On Android 13+ this maps to `POST_NOTIFICATIONS`; on Android 12 and below the current native Android SDK treats notification permission as granted because no runtime prompt exists.

Recommended product flow:

1. Initialize the SDK.
2. Explain why notifications are useful.
3. Call `requestPermission(activity)`.
4. Observe permission changes with `addPermissionObserver`.
5. If Android 12 or below returns disabled, guide the user to app notification settings.

## Notification Behavior

Sendrealm notifications are delivered as FCM messages containing a `sendrealm_v1` payload. The SDK parses that payload and can show the notification locally.

Supported Android display fields:

- `title`: notification title.
- `body`: notification text.
- `android.channelId`: notification channel ID. Default is `sendrealm_channel`.
- `android.smallIcon`: drawable resource name for the status bar icon.
- `android.largeIcon`: remote image URL for the large icon.
- `android.bigPicture`: remote image URL for expanded big picture style.
- `android.color`: accent color in Android color string format, usually `#RRGGBB`.
- `metadata.android_launch_url`: URL opened when the user taps the notification.
- `metadata.image_url`: fallback image URL.

### Images

Large icons and big pictures are loaded from remote URLs. Use HTTPS images that are small enough to download quickly. If an image fails to load, the SDK still displays the text notification.

### Icons

Android small notification icons should be drawable resources. Prefer a simple monochrome status bar icon. If a campaign references `smallIcon = "ic_stat_sendrealm"`, the app must include `res/drawable/ic_stat_sendrealm.*`. If the named icon is missing, the SDK falls back to the app icon, then to Android's default info icon.

### Sounds

Android sound behavior is controlled by notification channels on Android 8 and newer. Once a channel is created, Android keeps its importance and sound settings until the user changes or the app creates a new channel ID.

Dashboard-created Android channels are synced by the SDK. The SDK fetches channel definitions during initialization, after token registration/refresh, and periodically when the app resumes. This lets installed apps create dashboard channels locally before campaigns arrive.

Current guidance:

- Use distinct channel IDs for materially different sound/importance behavior.
- Keep campaign sound names aligned with resources in the host app.
- For custom sound support, add the sound file to `res/raw` and use a channel configured for that sound.
- If a sound does not play, check the notification channel settings in Android system settings first.
- If you change sound or importance for an existing channel ID, Android may keep the original device-level behavior. Create a new channel ID for materially different behavior.

### Deep Links And External Links

`metadata.android_launch_url` can be:

- An app deep link such as `myapp://orders/123`.
- An Android App Link such as `https://example.com/orders/123` that your app handles.
- An external URL such as `https://example.com`.

Tap behavior:

1. The SDK tracks `open`.
2. If a launch URL exists, the SDK tracks `click`.
3. The SDK tries to open the URL with Android intent resolution.
4. If no app can handle the URL, it falls back to opening the host app.

For external HTTPS URLs, make sure a browser exists on the device/emulator. For app deep links, make sure your host app has matching intent filters.

## Delivery Priority And Phone Brands

Sendrealm can send Android notifications with normal or high delivery priority. Priority is a hint to FCM and Android, not a guarantee.

| Device Family | Practical Notes |
| --- | --- |
| Google Pixel / stock Android | Usually follows FCM priority behavior closely. High priority is best for time-sensitive notifications. |
| Samsung | Battery optimization, sleeping apps, notification categories, and per-channel settings can affect delivery and display. High priority helps but does not override user or OEM restrictions. |
| Lenovo / Motorola | Background restrictions and battery saver modes can delay normal priority messages. High priority should be reserved for important user-visible notifications. |
| Other OEMs | Some vendors aggressively throttle background work. Keep notifications user-visible and avoid excessive high priority usage. |

Use high priority only for messages that should interrupt or promptly notify the user. Overusing high priority can reduce reliability over time because Android and FCM apply abuse protections.

## Events Tracked By The SDK

The SDK can track:

- `init`: device initialized.
- `register`: FCM token registered.
- `delivery`: notification received by the SDK.
- `foreground_display`: notification received while app is foregrounded.
- `background_notification_received`: data notification received in background.
- `open`: notification opened.
- `click`: notification opened with a launch URL.
- `dismiss`: notification dismissed.
- `opt_in`: user/device subscribed.
- `opt_out`: user/device unsubscribed.
- Custom events from `trackEvent(eventType, properties)`.

## API Reference

### `Sendrealm.init(context, appId, platform?, callback?)`

Convenience initializer. Uses default `SendrealmConfig` and platform `"android"` unless another value is provided.

Use `initialize(...)` for production apps because it exposes all configuration options.

### `Sendrealm.initialize(context, appId, config, callback?)`

Starts the SDK and registers the device.

Parameters:

- `context`: preferably the foreground `Activity` if `autoRequestPermission` is enabled.
- `appId`: Sendrealm app ID from the dashboard.
- `config`: `SendrealmConfig`.
- `callback`: receives the FCM token, or `null` if token registration failed.

### `SendrealmConfig.setBaseUrl(baseUrl)`

Sets the SDK API base URL. Optional for production because the SDK defaults to `https://sdk-api.sendrealm.com`; override it for local development and self-hosted environments.

### `SendrealmConfig.setExternalUserId(externalUserId)`

Sets the user ID to associate with this device during initialization. You can also call `login(...)` later.

### `SendrealmConfig.setUserEmail(userEmail)`

Sets the user email to associate with this device during initialization. You can also call `login(...)` later.

### `SendrealmConfig.setPlatform(platform)`

Sets the platform sent to the SDK API. Android apps should use `"android"`.

### `SendrealmConfig.setAutoRequestPermission(autoRequestPermission)`

Controls whether initialization should immediately request notification permission. Most apps should set this to `false` and ask after explaining the value to the user.

### `Sendrealm.requestPermission(activity)`

Requests Android 13+ notification permission. On Android 12 and below, no runtime prompt exists.

### `Sendrealm.hasNotificationPermission(context)`

Returns whether the SDK considers notifications allowed. On Android 13+ it checks `POST_NOTIFICATIONS`. On Android 12 and below, the native Android SDK currently treats notification permission as granted.

### `Sendrealm.addPermissionObserver(observer)`

Registers a listener for permission state changes.

```kotlin
Sendrealm.addPermissionObserver { granted ->
    Log.d("Push", "Permission granted: $granted")
}
```

Remove it with `removePermissionObserver(observer)`.

### `Sendrealm.addSubscriptionObserver(observer)`

Registers a listener for opt-in / opt-out state changes. Remove it with `removeSubscriptionObserver(observer)`.

### `Sendrealm.addNotificationClickListener(listener)`

Receives notification open events when the app handles an intent.

```kotlin
Sendrealm.addNotificationClickListener { result ->
    Log.d("Push", "Opened notification ${result.notificationId}")
}
```

Remove it with `removeNotificationClickListener(listener)`.

### `Sendrealm.addForegroundNotificationListener(listener)`

Receives notifications while the app is foregrounded. Call `event.preventDefault()` to stop the SDK from displaying the notification.

```kotlin
Sendrealm.addForegroundNotificationListener { event ->
    if (event.payload.data?.get("silent") == true) {
        event.preventDefault()
    }
}
```

Remove it with `removeForegroundNotificationListener(listener)`.

### `Sendrealm.login(userId, email?)`

Associates the current device with an external user ID and optional email. This refreshes registration so the API can link the device to a contact.

Call this after your app user signs in.

### `Sendrealm.logout()`

Clears the locally stored external user ID and email, then refreshes registration.

### `Sendrealm.getDeviceId()`

Returns the Sendrealm device ID after initialization, or `null` before initialization.

### `Sendrealm.isSubscribed()`

Returns the locally stored subscription state. Use `optIn` and `optOut` to update it.

### `Sendrealm.optIn(callback?)`

Fetches the current FCM token and subscribes/registers the device for push delivery.

### `Sendrealm.optOut(callback?)`

Unsubscribes the device. The API clears the send token for this device.

### `Sendrealm.syncNotificationChannels(callback?)`

Android only. Fetches dashboard channel definitions from Sendrealm and creates/deletes SDK-managed Android notification channels on the device. The SDK calls this automatically during initialization, app resume, and token refresh, but this method is useful while testing dashboard channel changes.

### `Sendrealm.addTag(key, value, callback?)`

Adds or updates one device/contact tag. Values can be strings, numbers, booleans, or `null`.

### `Sendrealm.addTags(tags, callback?)`

Adds or updates multiple tags.

```kotlin
Sendrealm.addTags(
    mapOf("plan" to "pro", "beta" to true)
) { success ->
    Log.d("Push", "Tags updated: $success")
}
```

Tags require the device to be linked to a contact. Call `login(...)` before tagging user-specific devices.

SDK tags are client-sourced values. Use them for app-observed preferences,
state, or behavior. Do not use SDK tags for authoritative account, billing,
security, compliance, or verified profile data; update those contact properties
from your server API instead.

### `Sendrealm.removeTag(key, callback?)`

Removes a tag by sending `null` for that key.

### `Sendrealm.trackEvent(eventType, properties?, callback?)`

Tracks a custom device event with optional JSON-compatible properties. Properties can be strings, numbers, booleans, nulls, arrays, or nested maps.

```kotlin
Sendrealm.trackEvent("app_opened")
Sendrealm.trackEvent(
    "checkout_started",
    mapOf(
        "product_id" to "sku_123",
        "product_name" to "Starter plan",
        "price" to 29.0
    )
)
```

Use stable event names. Prefer lowercase snake case.

### `Sendrealm.handleNotificationOpen(intent)`

Parses a notification open intent, notifies click listeners, tracks `open` and `click`, and returns a `SendrealmNotificationOpenResult`.

Call this from `onCreate` and `onNewIntent` for activities that can receive notification opens.

### `SendrealmNotificationOpenResult`

Returned by `handleNotificationOpen(intent)`:

- `notificationId`: Sendrealm notification ID.
- `launchUrl`: URL attached to the notification.
- `rawPayload`: raw `sendrealm_v1` payload.
- `payload`: parsed payload.

## Troubleshooting

### FCM token is null

Check:

- Emulator/device has Google Play services.
- Emulator has internet access.
- Firebase configuration returned by `/v1/init` matches the provider in the dashboard.
- The app's `applicationId` matches the Android package name configured for the Sendrealm push provider.
- Google/Firebase services are not temporarily unavailable.

### Notification does not open URL

Check:

- The campaign has `android_launch_url`.
- External URLs include `https://`.
- Deep links have matching intent filters.
- A browser or target app exists on the emulator/device.

### Notification does not display image

Check:

- Image URL is public HTTPS.
- Image is small enough to download quickly.
- Device has network access.

### Notification sound does not change

Check the Android notification channel. On Android 8+, sound is tied to the channel and may not change for an existing channel ID.
