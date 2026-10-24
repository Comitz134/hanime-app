# Project-specific ProGuard rules for composeApp Android release builds.

# Keep useful metadata for crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve Kotlin metadata/signatures needed by reflection/generics-heavy libraries.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations

# Ktor / Supabase client stack (runtime reflective paths in serializers/plugins).
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# Keep @Serializable generated serializers.
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep class com.nuvio.app.features.catalog.CatalogTargetKind { *; }

# Avoid R8 merging/optimizing the stream badge chip used in lazy stream rows.
-keep class com.nuvio.app.features.streams.StreamBadgeChipKt { *; }
-keep class com.nuvio.app.features.streams.StreamBadgeChipSize { *; }
-keep class com.nuvio.app.features.streams.StreamBadgeChipDefaults { *; }

-keep class com.nuvio.app.features.streams.StreamsScreenKt { *; }
-keep class com.nuvio.app.features.streams.StreamsScreenKt$* { *; }

# Avoid R8 producing verifier-invalid bytecode for the large player composable.
-keep class com.nuvio.app.features.player.PlayerScreenKt { *; }
-keep class com.nuvio.app.features.player.PlayerScreenKt$* { *; }

# QuickJS plugin runtime is dynamic; keep runtime and app plugin classes.
-keep class com.dokar.quickjs.** { *; }
-keep class com.nuvio.app.features.plugins.** { *; }

# P2P runtime and Nuvio Engine JNI bridge. Native libraries are not processed
# by R8, but their Kotlin/JNI wrapper classes and method names must stay stable.
-keep class com.nuvio.app.features.p2p.** { *; }
-keep class com.nuvio.engine.** { *; }
-keep interface com.nuvio.engine.** { *; }

-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# Media3 / ExoPlayer classes from local AAR decoders and stock modules.
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-keep class com.google.android.exoplayer2.** { *; }
-keep interface com.google.android.exoplayer2.** { *; }

-keep class is.xyz.mpv.** { *; }
-keep interface is.xyz.mpv.** { *; }

# Common optional security providers used by okhttp on some devices.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- Mihon extension runtime ------------------------------------------------
#
# An extension is a separate APK, and its bytecode reaches this app's classes by
# name. R8 renames whatever it is not told to keep, and a renamed host class is
# a NoClassDefFoundError on the phone the moment an extension loads - not
# something an in-process test can see. This is Mihon's own "common dependencies
# used in extensions" list, with the members external bytecode can reach left in
# place. `allowoptimization` keeps them renamable-free while still letting R8
# drop what nothing uses.
-keep,allowoptimization class androidx.preference.** { public protected *; }
-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }
-keep,allowoptimization class kotlinx.serialization.** { public protected *; }
-keep,allowoptimization class kotlin.time.** { public protected *; }
-keep,allowoptimization class okhttp3.** { public protected *; }
-keep,allowoptimization class okio.** { public protected *; }
-keep,allowoptimization class org.jsoup.** { public protected *; }
-keep,allowoptimization class rx.** { public protected *; }
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keep,allowoptimization class com.squareup.zstd.** { public protected *; }

# The API an extension compiles against: it subclasses eu.kanade.tachiyomi.source.*
# and is handed a NetworkHelper, so those names have to survive shrinking too.
-keep class eu.kanade.** { *; }

# Injekt learns which type a registration is for by reading the generic superclass
# of its type tokens (Mihon's own rule, verbatim). Without the signature every
# registration lands under "no type": the runtime starts, the extension loads,
# and its first `Injekt.get<Application>()` throws InjektionException - a failure
# that only appears in the minified build.
-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference

# Rhino is a desktop JVM engine: it initialises some of its own classes by name,
# and its Java-to-JSON bridge names java.beans, which Android does not ship. R8
# merging that bridge into a class the runtime initialises turned every script
# source (MangaDex) into ExceptionInInitializerError on the minified build, so
# the engine is kept as it ships. The suppressions stay for the references that
# are still never taken on a phone: java.beans, and the Swing debugger that
# ships in the same jar and cannot run on Android at all.
-keep class org.mozilla.javascript.** { *; }
-dontwarn java.beans.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
