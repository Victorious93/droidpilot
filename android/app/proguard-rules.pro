# --- kotlinx.serialization ---------------------------------------------------
# The compiler plugin generates a `Companion.serializer()` for each @Serializable class and
# looks it up reflectively. R8 cannot see that link, so without these rules the serializers
# are stripped and every encode fails at runtime in release builds only — the classic
# "works in debug, broken in the APK you shipped" failure.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# DroidPilot's own serializable model types.
-keep,includedescriptorclasses class com.mobilemcp.pro.protocol.**{ *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.UiNode { *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.Bounds { *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.UiTreeResult { *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.WaitResult { *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.Screenshot { *; }
-keep,includedescriptorclasses class com.mobilemcp.pro.automation.DeviceInfo { *; }

# Enum names cross the wire, so they must survive obfuscation.
-keepclassmembers enum com.mobilemcp.pro.core.ErrorCode { *; }

# --- Android entry points ----------------------------------------------------
# Instantiated by the framework from names in the manifest.
-keep class com.mobilemcp.pro.service.DroidPilotAccessibilityService { *; }
-keep class com.mobilemcp.pro.service.ServerForegroundService { *; }
-keep class com.mobilemcp.pro.DroidPilotApplication { *; }

# --- Java-WebSocket ----------------------------------------------------------
-keep class org.java_websocket.** { *; }
# Java-WebSocket compiles against SLF4J but the API is optional at runtime.
-dontwarn org.slf4j.**
