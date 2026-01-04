# Add project specific ProGuard rules here.

# Disable R8 full mode optimizations that break reflection
-dontoptimize

# Keep generic signatures and annotations for Retrofit/Gson
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

# Keep entire app package to prevent reflection issues
-keep class com.gatekey.client.** { *; }
-keepclassmembers class com.gatekey.client.** { *; }

# Retrofit - keep interfaces and method annotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep all Retrofit classes - important for generic type handling
-keep class retrofit2.** { *; }
-keepclassmembers class retrofit2.** { *; }

# Keep Retrofit response types with their generic signatures
-keep class retrofit2.Response { *; }
-keep class retrofit2.Call { *; }

# Keep Retrofit's internal type handling
-keep class retrofit2.Utils { *; }
-keep class retrofit2.ParameterHandler { *; }
-keep class retrofit2.ParameterHandler$* { *; }

# Dontwarn for common issues
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata { *; }

# Gson - comprehensive rules to fix ParameterizedType cast error
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }

# Keep TypeToken and related reflection classes - CRITICAL for generic types
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * extends com.google.gson.reflect.TypeToken {
    <fields>;
    <init>(...);
}

# Keep generic signatures for Gson deserialization
-keep class com.google.gson.internal.** { *; }

# Critical: Keep $Gson$Types which handles ParameterizedType
-keep class com.google.gson.internal.$Gson$Types { *; }
-keep class com.google.gson.internal.$Gson$Types$* { *; }

# Keep java.lang.reflect classes that Gson uses
-keepclassmembers class java.lang.reflect.** { *; }
-dontwarn java.lang.reflect.**

# Keep ParameterizedType and related reflection types
-keep class java.lang.reflect.ParameterizedType { *; }
-keep class java.lang.reflect.Type { *; }
-keep class java.lang.reflect.GenericArrayType { *; }
-keep class java.lang.reflect.WildcardType { *; }

# Prevent R8 from stripping classes that implement Type
-keep class * implements java.lang.reflect.Type { *; }
-keep class * implements java.lang.reflect.ParameterizedType { *; }

# Keep all data model classes with their fields and generic type info
-keep class com.gatekey.client.data.model.** { *; }
-keepclassmembers class com.gatekey.client.data.model.** {
    <fields>;
    <init>(...);
}

# Keep Result sealed class and its generic type
-keep class com.gatekey.client.data.repository.Result { *; }
-keep class com.gatekey.client.data.repository.Result$* { *; }

# Keep GatekeyApi interface methods with their return types
-keep interface com.gatekey.client.data.api.GatekeyApi { *; }
-keepclassmembernames interface com.gatekey.client.data.api.GatekeyApi {
    <methods>;
}

# Prevent R8 from stripping generic type information needed for Retrofit
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# Kotlin Serialization
-dontnote kotlinx.serialization.AnnotationsKt

# OpenVPN
-keep class de.blinkt.openvpn.** { *; }
-keep class org.spongycastle.** { *; }
