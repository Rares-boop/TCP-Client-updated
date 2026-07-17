# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Bouncy Castle — crypto nu se obfuscă
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Gson — modelele trebuie păstrate pentru serializare
-keep class chat.models.** { *; }
-keep class chat.network.** { *; }
-keep class chat.security.** { *; }

# Gson internals
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

