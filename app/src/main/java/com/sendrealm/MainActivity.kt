package com.sendrealm

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sendrealm.sdk.SendrealmConfig
import com.sendrealm.sdk.SendrealmForegroundNotificationListener
import com.sendrealm.sdk.SendrealmNotificationActionListener
import com.sendrealm.sdk.SendrealmNotificationChannelConfig
import com.sendrealm.sdk.SendrealmNotificationClickListener
import com.sendrealm.sdk.SendrealmNotificationOpenResult
import com.sendrealm.sdk.SendrealmPermissionObserver
import com.sendrealm.sdk.Sendrealm
import com.sendrealm.sdk.SendrealmSubscriptionObserver
import com.sendrealm.ui.theme.SendrealmTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class DemoNotificationEvent(
    val timestamp: String,
    val type: String,
    val notificationId: String?,
    val title: String?,
    val body: String?,
    val launchUrl: String?,
    val actionIdentifier: String?,
    val payloadData: String,
    val rawPayload: String?
)

class MainActivity : ComponentActivity() {
    private var appId by mutableStateOf("3NzKMzTQ")
    private var baseUrl by mutableStateOf(BuildConfig.SENDREALM_DEMO_BASE_URL)
    private var externalUserIdInput by mutableStateOf("android-demo-user")
    private var userEmailInput by mutableStateOf("android-demo@sendrealm.local")
    private var currentExternalUserId by mutableStateOf<String?>(null)
    private var currentUserEmail by mutableStateOf<String?>(null)
    private var tagKeyInput by mutableStateOf("plan")
    private var tagValueInput by mutableStateOf("pro")
    private var eventNameInput by mutableStateOf("demo_button_clicked")
    private var liveActivityIdInput by mutableStateOf("android-native-status-card")
    private var deviceId by mutableStateOf<String?>(null)
    private var sdkVersion by mutableStateOf("Unknown")
    private var lastInitToken by mutableStateOf<String?>(null)
    private var permissionGranted by mutableStateOf(false)
    private var subscribed by mutableStateOf(false)
    private var tokenStatus by mutableStateOf("Missing")
    private var channelStatus by mutableStateOf("Not inspected")
    private var diagnosticsSummary by mutableStateOf("No diagnostics loaded.")
    private var suppressForegroundDisplay by mutableStateOf(false)
    private var lastNotificationSummary by mutableStateOf("No notification activity yet.")
    private val activityLogs = mutableStateListOf<String>()
    private val notificationEvents = mutableStateListOf<DemoNotificationEvent>()

    private val permissionObserver = SendrealmPermissionObserver { granted ->
        runOnMain {
            permissionGranted = granted
            addLog(
                if (granted) {
                    "Notification permission granted"
                } else {
                    "Notification permission not granted"
                }
            )
        }
    }

    private val subscriptionObserver = SendrealmSubscriptionObserver { isSubscribed ->
        runOnMain {
            subscribed = isSubscribed
            addLog(
                if (isSubscribed) {
                    "Push subscription enabled"
                } else {
                    "Push subscription disabled"
                }
            )
        }
    }

    private val clickListener = SendrealmNotificationClickListener { result ->
        runOnMain {
            lastNotificationSummary = formatNotificationSummary(result)
            appendNotificationEvent(
                type = if (result.actionIdentifier.isNullOrBlank()) "Opened" else "Action Opened",
                notificationId = result.notificationId,
                title = result.payload?.notification?.title,
                body = result.payload?.notification?.body,
                launchUrl = result.launchUrl,
                actionIdentifier = result.actionIdentifier,
                payloadData = formatPayloadData(result.payload?.data),
                rawPayload = result.rawPayload
            )
            addLog(
                if (result.actionIdentifier.isNullOrBlank()) {
                    "Notification opened: ${result.notificationId ?: "unknown"}"
                } else {
                    "Notification action opened: ${result.actionIdentifier}"
                }
            )
            refreshSdkState()
        }
    }

    private val actionListener = SendrealmNotificationActionListener { event ->
        runOnMain {
            lastNotificationSummary =
                "Action ${event.actionIdentifier ?: "unknown"} on ${event.notificationId ?: "unknown-notification"}"
            appendNotificationEvent(
                type = "Action",
                notificationId = event.notificationId,
                title = event.payload?.notification?.title,
                body = event.payload?.notification?.body,
                launchUrl = event.launchUrl,
                actionIdentifier = event.actionIdentifier,
                payloadData = formatPayloadData(event.payload?.data),
                rawPayload = event.rawPayload
            )
            addLog("Notification action: ${event.actionIdentifier ?: "unknown"}")
            refreshSdkState()
        }
    }

    private val foregroundListener = SendrealmForegroundNotificationListener { event ->
        if (suppressForegroundDisplay) {
            event.preventDefault()
        }

        runOnMain {
            appendNotificationEvent(
                type = if (suppressForegroundDisplay) "Received (suppressed)" else "Received",
                notificationId = event.notificationId,
                title = event.payload.notification?.title,
                body = event.payload.notification?.body,
                launchUrl = event.launchUrl,
                actionIdentifier = null,
                payloadData = formatPayloadData(event.payload.data),
                rawPayload = event.rawPayload
            )
            addLog(
                buildString {
                    append("Foreground notification received")
                    event.notificationId?.let {
                        append(" (")
                        append(it)
                        append(")")
                    }
                    if (suppressForegroundDisplay) {
                        append(" - display suppressed")
                    }
                }
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Sendrealm.addPermissionObserver(permissionObserver)
        Sendrealm.addSubscriptionObserver(subscriptionObserver)
        Sendrealm.addNotificationClickListener(clickListener)
        Sendrealm.addNotificationActionListener(actionListener)
        Sendrealm.addForegroundNotificationListener(foregroundListener)

        refreshSdkState()
        appendNotificationEvent(
            type = "App Opened",
            notificationId = null,
            title = null,
            body = null,
            launchUrl = null,
            actionIdentifier = null,
            payloadData = buildString {
                append("appId=")
                append(appId)
                append('\n')
                append("permissionGranted=")
                append(permissionGranted)
                append('\n')
                append("subscribed=")
                append(subscribed)
                append('\n')
                append("deviceId=")
                append(deviceId ?: "unknown")
            },
            rawPayload = null
        )

        setContent {
            SendrealmTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoConsoleScreen(
                        modifier = Modifier.padding(innerPadding),
                        appId = appId,
                        baseUrl = baseUrl,
                        externalUserIdInput = externalUserIdInput,
                        userEmailInput = userEmailInput,
                        currentExternalUserId = currentExternalUserId,
                        currentUserEmail = currentUserEmail,
                        tagKeyInput = tagKeyInput,
                        tagValueInput = tagValueInput,
                        eventNameInput = eventNameInput,
                        liveActivityIdInput = liveActivityIdInput,
                        deviceId = deviceId,
                        sdkVersion = sdkVersion,
                        lastInitToken = lastInitToken,
                        tokenStatus = tokenStatus,
                        permissionGranted = permissionGranted,
                        subscribed = subscribed,
                        channelStatus = channelStatus,
                        diagnosticsSummary = diagnosticsSummary,
                        suppressForegroundDisplay = suppressForegroundDisplay,
                        lastNotificationSummary = lastNotificationSummary,
                        notificationEvents = notificationEvents,
                        logs = activityLogs,
                        onAppIdChange = { appId = it },
                        onBaseUrlChange = { baseUrl = it },
                        onExternalUserIdChange = { externalUserIdInput = it },
                        onUserEmailChange = { userEmailInput = it },
                        onTagKeyChange = { tagKeyInput = it },
                        onTagValueChange = { tagValueInput = it },
                        onEventNameChange = { eventNameInput = it },
                        onLiveActivityIdChange = { liveActivityIdInput = it },
                        onSuppressForegroundDisplayChange = {
                            suppressForegroundDisplay = it
                            addLog(
                                if (it) {
                                    "Foreground notifications will be suppressed"
                                } else {
                                    "Foreground notifications will be displayed"
                                }
                            )
                        },
                        onInitializeClick = { initializeSdk() },
                        onRefreshStatusClick = { refreshSdkState() },
                        onRequestPermissionClick = { requestNotificationPermission() },
                        onLoginClick = { loginUser() },
                        onLogoutClick = { logoutUser() },
                        onOptInClick = { optIn() },
                        onOptOutClick = { optOut() },
                        onAddTagClick = { upsertTag() },
                        onTrackEventClick = { trackEvent() },
                        onCreateDemoChannelClick = { createDemoChannel() },
                        onSyncChannelsClick = { syncNotificationChannels() },
                        onListChannelsClick = { loadNotificationChannels() },
                        onRegisterLiveActivityTokenClick = { registerLiveActivityToken() },
                        onTrackLiveActivityStartClick = { trackLiveActivity("start") },
                        onTrackLiveActivityUpdateClick = { trackLiveActivity("update") },
                        onTrackLiveActivityEndClick = { trackLiveActivity("end") },
                        onLoadDiagnosticsClick = { loadDiagnostics() }
                    )
                }
            }
        }

        addLog("Demo app ready")
        initializeSdk()
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    override fun onDestroy() {
        Sendrealm.removePermissionObserver(permissionObserver)
        Sendrealm.removeSubscriptionObserver(subscriptionObserver)
        Sendrealm.removeNotificationClickListener(clickListener)
        Sendrealm.removeNotificationActionListener(actionListener)
        Sendrealm.removeForegroundNotificationListener(foregroundListener)
        super.onDestroy()
    }

    private fun initializeSdk() {
        val normalizedAppId = appId.trim()
        val normalizedBaseUrl = baseUrl.trim()

        if (normalizedAppId.isEmpty()) {
            addLog("App ID is required before initialization")
            return
        }

        val config = SendrealmConfig()
            .setBaseUrl(normalizedBaseUrl.ifEmpty { null })
            .setPlatform("android")
            .setAutoRequestPermission(false)
            .setExternalUserId(externalUserIdInput.trim().ifEmpty { null })
            .setUserEmail(userEmailInput.trim().ifEmpty { null })

        addLog("Initializing SDK for appId=$normalizedAppId")

        Sendrealm.initialize(this, normalizedAppId, config) { token ->
            runOnMain {
                lastInitToken = token
                refreshSdkState()
                addLog(
                    if (token != null) {
                        "SDK initialized and token registered"
                    } else {
                        "SDK initialization failed"
                    }
                )
            }
        }
    }

    private fun requestNotificationPermission() {
        Sendrealm.requestPermission(this)
        addLog("Requested notification permission")
        window.decorView.postDelayed(
            { refreshSdkState() },
            1200
        )
    }

    private fun loginUser() {
        val userId = externalUserIdInput.trim()
        val email = userEmailInput.trim().ifEmpty { null }

        if (userId.isEmpty()) {
            addLog("External user ID is required to login")
            return
        }

        Sendrealm.login(userId, email)
        refreshSdkState()
        addLog("Login requested for $userId")
    }

    private fun logoutUser() {
        Sendrealm.logout()
        refreshSdkState()
        addLog("Logout requested")
    }

    private fun optIn() {
        Sendrealm.optIn { success ->
            runOnMain {
                refreshSdkState()
                addLog(
                    if (success) {
                        "Opt-in synced with the server"
                    } else {
                        "Opt-in failed"
                    }
                )
            }
        }
    }

    private fun optOut() {
        Sendrealm.optOut { success ->
            runOnMain {
                refreshSdkState()
                addLog(
                    if (success) {
                        "Opt-out synced with the server"
                    } else {
                        "Opt-out failed"
                    }
                )
            }
        }
    }

    private fun upsertTag() {
        val key = tagKeyInput.trim()
        val value = tagValueInput.trim().ifEmpty { null }

        if (key.isEmpty()) {
            addLog("Tag key is required")
            return
        }

        Sendrealm.addTag(key, value) { success ->
            runOnMain {
                addLog(
                    if (success) {
                        if (value == null) {
                            "Tag removed: $key"
                        } else {
                            "Tag synced: $key=$value"
                        }
                    } else {
                        "Tag update failed for $key"
                    }
                )
            }
        }
    }

    private fun trackEvent() {
        val eventName = eventNameInput.trim()

        if (eventName.isEmpty()) {
            addLog("Event name is required")
            return
        }

        Sendrealm.trackEvent(
            eventName,
            mapOf(
                "source" to "android_demo",
                "sample_product_id" to "sku_demo_123",
                "sample_price" to 29.0
            )
        ) { success ->
            runOnMain {
                addLog(
                    if (success) {
                        "Tracked event: $eventName"
                    } else {
                        "Event tracking failed for $eventName"
                    }
                )
            }
        }
    }

    private fun createDemoChannel() {
        val success = Sendrealm.createNotificationChannel(
            this,
            SendrealmNotificationChannelConfig(
                id = "sendrealm-demo",
                name = "Sendrealm Demo",
                description = "Dashboard test notifications",
                importance = NotificationManager.IMPORTANCE_HIGH,
                enableVibration = true,
                enableLights = true,
                lightColor = 0xFFE5B44D.toInt(),
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC,
                showBadge = true
            )
        )

        addLog(
            if (success) {
                "Created Android channel sendrealm-demo"
            } else {
                "Channel create failed"
            }
        )
        loadNotificationChannels()
    }

    private fun syncNotificationChannels() {
        Sendrealm.syncNotificationChannels { success ->
            runOnMain {
                addLog(
                    if (success) {
                        "Synced dashboard notification channels"
                    } else {
                        "Channel sync failed"
                    }
                )
                loadNotificationChannels()
            }
        }
    }

    private fun loadNotificationChannels() {
        val channels = Sendrealm.getNotificationChannels(this)
        channelStatus = if (channels.isEmpty()) {
            "No Android notification channels found"
        } else {
            channels.joinToString(separator = "\n") { channel ->
                "${channel.id} / ${channel.name} / importance ${channel.importance}"
            }
        }
        addLog("Android channels: $channelStatus")
    }

    private fun registerLiveActivityToken() {
        val activityId = liveActivityIdInput.trim()
        val token = Sendrealm.getState(this).registrationToken

        if (token.isNullOrBlank()) {
            addLog("Refresh/register the push token before syncing Live Activity token")
            return
        }

        Sendrealm.registerLiveActivityToken(
            token = token,
            activityId = activityId.ifEmpty { null },
            tokenType = "android_registration",
            activityType = "android_ongoing_status",
            attributesType = "android_ongoing_status"
        ) { success ->
            runOnMain {
                addLog(
                    if (success) {
                        "Registered Android Live Activity token for ${activityId.ifEmpty { "device" }}"
                    } else {
                        "Android Live Activity token registration failed"
                    }
                )
                refreshSdkState()
            }
        }
    }

    private fun trackLiveActivity(eventType: String) {
        val activityId = liveActivityIdInput.trim()

        if (activityId.isEmpty()) {
            addLog("Activity ID is required for Live Activity event tracking")
            return
        }

        Sendrealm.trackEvent(
            "live_activity_$eventType",
            mapOf(
                "activity_id" to activityId,
                "source" to "android_native_demo",
                "demo_app" to "Sendrealm Native",
                "platform" to "android"
            )
        ) { success ->
            runOnMain {
                addLog(
                    if (success) {
                        "Tracked live_activity_$eventType for $activityId"
                    } else {
                        "Live Activity $eventType tracking failed"
                    }
                )
            }
        }
    }

    private fun loadDiagnostics() {
        val diagnostics = Sendrealm.getDiagnostics(this)
        diagnosticsSummary = buildString {
            append("SDK ")
            append(diagnostics.sdkVersion)
            append('\n')
            append(diagnostics.platform)
            append(" / ")
            append(diagnostics.deviceModel)
            append('\n')
            append("API ")
            append(diagnostics.apiUrl)
            append('\n')
            append("Device ")
            append(diagnostics.deviceId ?: "not registered")
            append('\n')
            append("Permission ")
            append(diagnostics.permissionStatus)
            append('\n')
            append("Subscribed ")
            append(if (diagnostics.subscribed) "yes" else "no")
            append('\n')
            append("Token ")
            append(if (diagnostics.registrationTokenPresent) "present" else "missing")
            append('\n')
            append("Queues events=")
            append(diagnostics.queueCounts.events)
            append(" tags=")
            append(diagnostics.queueCounts.tags)
            append(" registrations=")
            append(diagnostics.queueCounts.registrations)
            append('\n')
            append(
                diagnostics.lastSdkError?.let {
                    "Last SDK error ${it.code}: ${it.message}"
                } ?: "Last SDK error none"
            )
        }
        addLog("Diagnostics loaded")
    }

    private fun handleNotificationIntent(intent: Intent?) {
        Sendrealm.handleNotificationOpen(intent)?.let { result ->
            lastNotificationSummary = formatNotificationSummary(result)
        }
    }

    private fun refreshSdkState() {
        val state = Sendrealm.getState(this)

        permissionGranted = state.permissionGranted
        deviceId = state.deviceId
        subscribed = state.subscribed
        sdkVersion = state.sdkVersion
        tokenStatus = state.tokenStatus
        currentExternalUserId = state.externalUserId
        currentUserEmail = state.userEmail
    }

    private fun formatNotificationSummary(result: SendrealmNotificationOpenResult): String {
        return buildString {
            append(result.notificationId ?: "unknown-notification")
            result.payload?.notification?.title?.let {
                append(" | ")
                append(it)
            }
            result.launchUrl?.takeIf { it.isNotBlank() }?.let {
                append(" | ")
                append(it)
            }
            result.actionIdentifier?.takeIf { it.isNotBlank() }?.let {
                append(" | action ")
                append(it)
            }
        }
    }

    private fun formatPayloadData(data: Map<String, Any?>?): String {
        if (data.isNullOrEmpty()) {
            return "No custom data"
        }

        return data.entries.joinToString(separator = "\n") { (key, value) ->
            "$key=${value ?: "null"}"
        }
    }

    private fun appendNotificationEvent(
        type: String,
        notificationId: String?,
        title: String?,
        body: String?,
        launchUrl: String?,
        actionIdentifier: String?,
        payloadData: String,
        rawPayload: String?
    ) {
        notificationEvents.add(
            0,
            DemoNotificationEvent(
                timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()),
                type = type,
                notificationId = notificationId,
                title = title,
                body = body,
                launchUrl = launchUrl,
                actionIdentifier = actionIdentifier,
                payloadData = payloadData,
                rawPayload = rawPayload
            )
        )

        while (notificationEvents.size > 20) {
            notificationEvents.removeAt(notificationEvents.lastIndex)
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            runOnUiThread(action)
        }
    }

    private fun addLog(message: String) {
        runOnMain {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            activityLogs.add(0, "[$timestamp] $message")
            while (activityLogs.size > 40) {
                activityLogs.removeAt(activityLogs.lastIndex)
            }
            Log.d("SendrealmSample", message)
        }
    }
}

@Composable
private fun DemoConsoleScreen(
    modifier: Modifier = Modifier,
    appId: String,
    baseUrl: String,
    externalUserIdInput: String,
    userEmailInput: String,
    currentExternalUserId: String?,
    currentUserEmail: String?,
    tagKeyInput: String,
    tagValueInput: String,
    eventNameInput: String,
    liveActivityIdInput: String,
    deviceId: String?,
    sdkVersion: String,
    lastInitToken: String?,
    tokenStatus: String,
    permissionGranted: Boolean,
    subscribed: Boolean,
    channelStatus: String,
    diagnosticsSummary: String,
    suppressForegroundDisplay: Boolean,
    lastNotificationSummary: String,
    notificationEvents: List<DemoNotificationEvent>,
    logs: List<String>,
    onAppIdChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onExternalUserIdChange: (String) -> Unit,
    onUserEmailChange: (String) -> Unit,
    onTagKeyChange: (String) -> Unit,
    onTagValueChange: (String) -> Unit,
    onEventNameChange: (String) -> Unit,
    onLiveActivityIdChange: (String) -> Unit,
    onSuppressForegroundDisplayChange: (Boolean) -> Unit,
    onInitializeClick: () -> Unit,
    onRefreshStatusClick: () -> Unit,
    onRequestPermissionClick: () -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onOptInClick: () -> Unit,
    onOptOutClick: () -> Unit,
    onAddTagClick: () -> Unit,
    onTrackEventClick: () -> Unit,
    onCreateDemoChannelClick: () -> Unit,
    onSyncChannelsClick: () -> Unit,
    onListChannelsClick: () -> Unit,
    onRegisterLiveActivityTokenClick: () -> Unit,
    onTrackLiveActivityStartClick: () -> Unit,
    onTrackLiveActivityUpdateClick: () -> Unit,
    onTrackLiveActivityEndClick: () -> Unit,
    onLoadDiagnosticsClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Sendrealm SDK Test Console",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Use this screen to validate the Android SDK flow before testing dashboard campaigns.",
            style = MaterialTheme.typography.bodyMedium
        )

        DemoSection(
            title = "Status",
            description = "These values confirm whether the app is ready to receive push campaigns."
        ) {
            StatusRow("Device ID", deviceId ?: "Not registered yet")
            StatusRow("SDK", sdkVersion)
            StatusRow("Platform", "android")
            StatusRow("Token", tokenStatus)
            StatusRow("Permission", if (permissionGranted) "Granted" else "Not granted")
            StatusRow("Subscribed", if (subscribed) "Yes" else "No")
            StatusRow("Current User", currentExternalUserId ?: "Anonymous device")
            StatusRow("User Email", currentUserEmail ?: "Not set")
            StatusRow("Last Token", lastInitToken ?: "No token yet")
            StatusRow("Channels", channelStatus)
            StatusRow("Last Notification", lastNotificationSummary)

            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onInitializeClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Initialize")
                }
                OutlinedButton(
                    onClick = onRefreshStatusClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh Status")
                }
            }
        }

        DemoSection(
            title = "Configuration",
            description = "Match these values to the push app you use in the dashboard."
        ) {
            OutlinedTextField(
                value = appId,
                onValueChange = onAppIdChange,
                label = { Text("Push App ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                label = { Text("API Base URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRequestPermissionClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Notification Permission")
            }
        }

        DemoSection(
            title = "Identity",
            description = "Login is required if you want to test segment campaigns, aliases, or tags."
        ) {
            OutlinedTextField(
                value = externalUserIdInput,
                onValueChange = onExternalUserIdChange,
                label = { Text("External User ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = userEmailInput,
                onValueChange = onUserEmailChange,
                label = { Text("User Email (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onLoginClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Login User")
                }
                OutlinedButton(
                    onClick = onLogoutClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Logout User")
                }
            }
        }

        DemoSection(
            title = "Subscription",
            description = "These actions let you verify that dashboard campaigns respect opt-in state."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onOptInClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Opt In")
                }
                OutlinedButton(
                    onClick = onOptOutClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Opt Out")
                }
            }
        }

        DemoSection(
            title = "Tags And Events",
            description = "Use these to validate server-side tags, segmentation, and custom analytics."
        ) {
            OutlinedTextField(
                value = tagKeyInput,
                onValueChange = onTagKeyChange,
                label = { Text("Tag Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = tagValueInput,
                onValueChange = onTagValueChange,
                label = { Text("Tag Value (leave blank to remove)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onAddTagClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sync Tag")
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = eventNameInput,
                onValueChange = onEventNameChange,
                label = { Text("Custom Event Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onTrackEventClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Track Custom Event")
            }
        }

        DemoSection(
            title = "Android Delivery",
            description = "Use these checks for notification channels, action buttons, and Android ongoing Live Activities."
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onCreateDemoChannelClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Create Channel")
                }
                OutlinedButton(
                    onClick = onSyncChannelsClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Sync Channels")
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onListChannelsClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("List Channels")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = channelStatus,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = liveActivityIdInput,
                onValueChange = onLiveActivityIdChange,
                label = { Text("Live Activity activity_id") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRegisterLiveActivityTokenClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Register Live Activity Token")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onTrackLiveActivityStartClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Track Start")
                }
                OutlinedButton(
                    onClick = onTrackLiveActivityUpdateClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Track Update")
                }
                OutlinedButton(
                    onClick = onTrackLiveActivityEndClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Track End")
                }
            }
        }

        DemoSection(
            title = "Diagnostics",
            description = "Redacted SDK state for support and installation checks."
        ) {
            Button(
                onClick = onLoadDiagnosticsClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Support Diagnostics")
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = diagnosticsSummary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }

        DemoSection(
            title = "Foreground Testing",
            description = "Helpful when validating how notifications behave while the app is open."
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Suppress foreground notification display",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "When enabled, the foreground listener prevents the system notification from appearing.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = suppressForegroundDisplay,
                    onCheckedChange = onSuppressForegroundDisplayChange
                )
            }
        }

        DemoSection(
            title = "Notification Events",
            description = "Shows app lifecycle and notification events, including opened, clicked, received, and payload data."
        ) {
            if (notificationEvents.isEmpty()) {
                Text(
                    text = "No notification events captured yet.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                notificationEvents.forEach { event ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "${event.timestamp} • ${event.type}",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Notification ID: ${event.notificationId ?: "unknown"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Title: ${event.title ?: "(none)"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Body: ${event.body ?: "(none)"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Launch URL: ${event.launchUrl ?: "(none)"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Action ID: ${event.actionIdentifier ?: "(none)"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Payload Data",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = event.payloadData,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = "Raw Payload",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = event.rawPayload ?: "(none)",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        DemoSection(
            title = "Event Log",
            description = "Recent SDK actions and notification activity."
        ) {
            if (logs.isEmpty()) {
                Text("No events yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                logs.forEach { entry ->
                    Text(
                        text = entry,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun DemoSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(10.dp))
    }
}
