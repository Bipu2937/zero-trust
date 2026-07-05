# React Native
-keep class com.facebook.react.** { *; }
-keep class com.facebook.hermes.** { *; }
-keep class com.facebook.jni.** { *; }

# Vault native modules are looked up reflectively by the RN bridge.
-keep class com.zerotrustvault.vault.** { *; }

# Strip all logging from release builds — decrypted metadata must never
# reach logcat, where any app holding READ_LOGS (or adb) could see it.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
