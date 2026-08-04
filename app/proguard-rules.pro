# Shrink, but do not RENAME.
#
# Roam is a personal, sideloaded app: obfuscation protects nothing here, and it
# costs a great deal. yt-dlp, Jackson, Media3 and the Google API client all
# read classes back by reflection, so renaming breaks them at RUNTIME and never
# at build time -- the symptom is a meaningless error like "class R4.a is not a
# concrete class" from a search that works perfectly in a debug build, and each
# fix is another keep rule guessed at from an obfuscated name.
#
# -dontobfuscate keeps the shrinking (which is what actually saves size) and
# drops only the renaming. If this is ever removed, expect reflective libraries
# to need exhaustive keep rules, and expect to find that out from a user rather
# than from CI.
-dontobfuscate

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

# commons-compress, which yt-dlp uses to unpack its Python runtime.
#
# -dontwarn is NOT enough: this needs -keep. ExtraFieldUtils registers each
# ZipExtraField implementation reflectively and checks it is instantiable, so
# once R8 shrinks away an unreferenced no-arg constructor the check fails with
# "class org.apache.commons.compress.archivers.zip.AsiExtraField is not a
# concrete class" -- nothing calls those constructors from Kotlin, so R8 is
# right that they are unused and wrong that they are removable.
-keep class org.apache.commons.compress.** { *; }
-keep interface org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
