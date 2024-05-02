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

# Ktor Client okhttp3
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn javax.persistence.**

# Kotlinx Serialization
-keep,includedescriptorclasses class earth.worldwind.**$$serializer { *; }

# OrmLite models
-dontwarn com.j256.ormlite.**
-keep class com.j256.ormlite.**
-keep @com.j256.ormlite.table.DatabaseTable class * { *; }