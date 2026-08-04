# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Google API client uses reflection over model classes
-keep class com.google.api.** { *; }
-keep class com.google.api.client.** { *; }
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class app.roam.**$$serializer { *; }
-keepclassmembers class app.roam.** {
    *** Companion;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# yt-dlp. The library loads native code and reads its own model classes back
# by reflection, so R8 renaming them breaks it at runtime and not at build
# time -- the symptom is an obfuscated class name like "w5.e" surfacing as the
# error from a search that worked fine in a debug build.
-keep class com.yausername.** { *; }
-keep interface com.yausername.** { *; }
-dontwarn com.yausername.**
# Its JSON models are Jackson-annotated and deserialised by name.
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* *;
}
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.compress.**
