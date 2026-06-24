-keep class com.sendrealm.sdk.** { *; }
-keepclassmembers class * extends com.google.firebase.messaging.FirebaseMessagingService {
    public void onMessageReceived(com.google.firebase.messaging.RemoteMessage);
    public void onNewToken(java.lang.String);
}
-keepattributes Signature
-keepattributes *Annotation*
