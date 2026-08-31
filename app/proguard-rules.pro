# Framework-neutral floor (required for reflection-based serialization)
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlin.Metadata { *; }

# Room
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# SQLCipher (net.zetetic:sqlcipher-android -- classes are looked up via JNI,
# so R8 must not rename/strip them. The old net.sqlcipher.** package belongs to
# the deprecated android-database-sqlcipher artifact, not the one this app uses.)
-keep,includedescriptorclasses class net.zetetic.database.** { *; }
-keep,includedescriptorclasses interface net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# kotlinx.serialization -- keep generated serializers for @Serializable classes
-keepattributes *Annotation*, InnerClasses
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keepclasseswithmembers class **$$serializer {
    static ** INSTANCE;
}

# Glance widgets -- keep receiver classes so the launcher can find them
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }

