# Sendrealm Android Library

This is the Android library module for the native Kotlin SDK.

## Production APIs

- `Sendrealm.getDiagnostics(context)` returns app/API/SDK metadata, token presence, permission status, subscribed state, queue counts, last init/register result, last SDK error, and last payloads.
- `Sendrealm.getPermissionStatus(context)` returns `not_determined`, `authorized`, or `denied`.
- `Sendrealm.openNotificationSettings(context)` opens system notification settings.
- `Sendrealm.setBadgeCount(context, count)` and `clearBadge(context)` persist and broadcast launcher badge counts on a best-effort basis.
- `Sendrealm.setForegroundPresentation(context, SendrealmForegroundPresentationOptions(display = false))` suppresses SDK foreground display while still forwarding foreground events.
- `addNotificationActionListener` and `addSilentNotificationListener` forward action taps and data-only/background payloads.

Failed token registration and subscription updates are queued locally and retried when the app resumes. Event and tag queues continue to flush after registration succeeds.

Read the full SDK documentation here:

- [Android Kotlin SDK guide](../README.md)
