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
# Glance maps a placed widget to its provider by CLASS NAME (GlanceAppWidgetManager
# records receiver -> glanceAppWidget.javaClass.name, and updateAll() looks placed
# instances up by it). Without this rule R8 horizontally merges NextRemindersWidget
# and StockLevelsWidget into one class with an int class-id constructor, and
# updating one widget redraws every widget on the launcher (§7 R69, verified in
# the DEX 2026-09-14). tools/r8_check.py fails the build if the names are gone.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }

