# ProGuard Rules for TPCore — Memory Shrinking Strategy
# Target: Java 21 / 25 | PaperMC & Folia Single-JAR

-target 21

# Optimization & Shrinking Settings
-dontobfuscate
-dontoptimize
-dontwarn **
-ignorewarnings

# Keep Annotations & Signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# Keep Main Plugin Class (JavaPlugin Composition Root)
-keep public class com.lunatech.tpcore.TPCore {
    public void onEnable();
    public void onDisable();
}

# Keep Paper / Bukkit Listener EventHandlers
-keepclassmembers class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

# Keep Configurate 4.x @ConfigSerializable Records & Classes
-keep @org.spongepowered.configurate.objectmapping.ConfigSerializable class * { *; }
-keepclassmembers class * extends java.lang.Record { *; }

# Keep JDBC, SQLite, and javax.sql DataSources
-keep class java.sql.** { *; }
-keep interface java.sql.** { *; }
-keep class javax.sql.** { *; }
-keep interface javax.sql.** { *; }
-keep class org.sqlite.** { *; }

# Keep Relocated HikariCP Pool & Utilities
-keep class com.lunatech.tpcore.libs.hikari.** { *; }
-keepclassmembers class * implements javax.sql.DataSource { *; }
-keepclassmembers class * implements java.sql.Driver { *; }

# Keep Relocated Configurate Libraries
-keep class com.lunatech.tpcore.libs.configurate.** { *; }

# Keep Enum Classes & Members
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
